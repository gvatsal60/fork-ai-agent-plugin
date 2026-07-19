package io.jenkins.plugins.aiagentjob;

import hudson.Proc;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Minimal synchronous Agent Client Protocol client for one agent prompt. */
final class AcpClientSession {
    private static final String JSONRPC_VERSION = "2.0";

    private final Proc proc;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AiAgentExecutor.AgentOutputHandler outputHandler;
    private final ExecutionRegistry.LiveExecution liveExecution;
    private final Duration approvalTimeout;
    private long nextRequestId;

    AcpClientSession(
            Proc proc,
            InputStream stdout,
            OutputStream stdin,
            AiAgentExecutor.AgentOutputHandler outputHandler,
            ExecutionRegistry.LiveExecution liveExecution,
            Duration approvalTimeout) {
        this.proc = proc;
        this.reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));
        this.outputHandler = outputHandler;
        this.liveExecution = liveExecution;
        this.approvalTimeout = approvalTimeout;
    }

    boolean execute(String cwd, String prompt, String model, String reasoningEffort)
            throws IOException, InterruptedException {
        try {
            initialize();
            String sessionId = newSession(cwd);
            setConfigOption(sessionId, "model", model);
            setConfigOption(sessionId, "effort", reasoningEffort);
            prompt(sessionId, prompt);
            return true;
        } catch (ApprovalDeniedException e) {
            return false;
        }
    }

    private void initialize() throws IOException, InterruptedException {
        JSONObject fileSystem = object("readTextFile", false, "writeTextFile", false);
        JSONObject capabilities = object("fs", fileSystem, "terminal", false);
        JSONObject params = object("protocolVersion", 1, "clientCapabilities", capabilities);
        request("initialize", params);
    }

    private String newSession(String cwd) throws IOException, InterruptedException {
        JSONObject params = object("cwd", cwd, "mcpServers", new JSONArray());
        JSONObject result = request("session/new", params);
        String sessionId = result.optString("sessionId", "").trim();
        if (sessionId.isEmpty()) {
            throw new IOException("ACP agent returned no session ID.");
        }
        return sessionId;
    }

    private void setConfigOption(String sessionId, String configId, String value)
            throws IOException, InterruptedException {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        JSONObject params =
                object("sessionId", sessionId, "configId", configId, "value", value.trim());
        request("session/set_config_option", params);
    }

    private void prompt(String sessionId, String prompt) throws IOException, InterruptedException {
        JSONArray content = new JSONArray();
        content.add(object("type", "text", "text", prompt));
        request("session/prompt", object("sessionId", sessionId, "prompt", content));
    }

    private JSONObject request(String method, JSONObject params)
            throws IOException, InterruptedException {
        long requestId = ++nextRequestId;
        send(
                object(
                        "jsonrpc",
                        JSONRPC_VERSION,
                        "id",
                        requestId,
                        "method",
                        method,
                        "params",
                        params));

        while (true) {
            JSONObject message = readMessage();
            String incomingMethod = message.optString("method", "");
            if ("session/request_permission".equals(incomingMethod) && message.has("id")) {
                handlePermissionRequest(message);
                continue;
            }
            if (!incomingMethod.isEmpty() && message.has("id")) {
                sendMethodNotFound(message.opt("id"), incomingMethod);
                continue;
            }
            if (!sameId(message.opt("id"), requestId)) {
                continue;
            }

            JSONObject error = message.optJSONObject("error");
            if (error != null) {
                outputHandler.recordLine(message.toString());
                String errorMessage = error.optString("message", error.toString());
                throw new IOException("ACP " + method + " failed: " + errorMessage);
            }
            JSONObject result = message.optJSONObject("result");
            if (result == null) {
                throw new IOException("ACP " + method + " returned no result.");
            }
            if ("session/prompt".equals(method)) {
                outputHandler.recordLine(message.toString());
            }
            return result;
        }
    }

    private JSONObject readMessage() throws IOException, InterruptedException {
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                int exitCode = proc.join();
                throw new IOException(
                        "ACP agent exited with code " + exitCode + " before completing request.");
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                outputHandler.recordLine(line);
                continue;
            }
            try {
                JSONObject message = JSONObject.fromObject(trimmed);
                if (shouldRecord(message)) {
                    outputHandler.recordLine(line);
                }
                return message;
            } catch (RuntimeException e) {
                outputHandler.recordLine(line);
                throw new IOException("ACP agent returned invalid JSON: " + trimmed, e);
            }
        }
    }

    private static boolean shouldRecord(JSONObject message) {
        String method = message.optString("method", "");
        if ("session/request_permission".equals(method)) {
            return true;
        }
        if (!"session/update".equals(method)) {
            return false;
        }
        JSONObject params = message.optJSONObject("params");
        JSONObject update = params == null ? null : params.optJSONObject("update");
        if (update == null) {
            return false;
        }
        String type = update.optString("sessionUpdate", "");
        return "agent_message_chunk".equals(type)
                || "user_message_chunk".equals(type)
                || "agent_thought_chunk".equals(type)
                || "tool_call".equals(type)
                || "tool_call_update".equals(type)
                || "usage_update".equals(type);
    }

    private void handlePermissionRequest(JSONObject request)
            throws IOException, InterruptedException {
        JSONObject params = request.optJSONObject("params");
        JSONObject toolCall = params == null ? null : params.optJSONObject("toolCall");
        if (params == null || toolCall == null) {
            sendMethodNotFound(request.opt("id"), "session/request_permission");
            return;
        }

        String toolCallId =
                LogFormatUtils.firstNonEmpty(toolCall, "toolCallId", "tool_call_id", "id");
        if (toolCallId.isEmpty()) {
            toolCallId = String.valueOf(request.opt("id"));
        }
        String toolName = LogFormatUtils.firstNonEmpty(toolCall, "kind", "title");
        if (toolName.isEmpty()) {
            toolName = "tool";
        }
        JSONObject rawInput = toolCall.optJSONObject("rawInput");
        String summary = LogFormatUtils.extractToolInput(rawInput, toolName);
        if (summary.isEmpty()) {
            summary = LogFormatUtils.firstNonEmpty(toolCall, "title");
        }

        toolCallId = outputHandler.maskSensitiveValues(toolCallId);
        toolName = outputHandler.maskSensitiveValues(toolName);
        summary = outputHandler.maskSensitiveValues(summary);

        ExecutionRegistry.PendingApproval pending =
                liveExecution.createPendingApproval(toolCallId, toolName, summary);
        outputHandler.writeStatus(
                "Approval required: "
                        + pending.getToolName()
                        + " ("
                        + pending.getToolCallId()
                        + ")");

        ExecutionRegistry.ApprovalDecision decision =
                liveExecution.awaitDecision(pending, approvalTimeout);
        if (!proc.isAlive()) {
            outputHandler.writeStatus("Approval denied: " + decision.getReason());
            throw new ApprovalDeniedException();
        }
        JSONArray options = params.optJSONArray("options");
        String optionId =
                findPermissionOption(
                        options,
                        decision.isApproved()
                                ? new String[] {"allow_once"}
                                : new String[] {"reject_once", "reject_always"});
        boolean approved = decision.isApproved() && optionId != null;

        JSONObject outcome;
        if (optionId == null) {
            outcome = object("outcome", "cancelled");
        } else {
            outcome = object("outcome", "selected", "optionId", optionId);
        }
        send(
                object(
                        "jsonrpc",
                        JSONRPC_VERSION,
                        "id",
                        request.opt("id"),
                        "result",
                        object("outcome", outcome)));

        if (!approved) {
            String reason =
                    decision.isApproved()
                            ? "agent did not offer an allow-once option"
                            : decision.getReason();
            outputHandler.writeStatus("Approval denied: " + reason);
            throw new ApprovalDeniedException();
        }
        outputHandler.writeStatus("Approval granted: " + pending.getToolName());
    }

    private static String findPermissionOption(JSONArray options, String[] acceptedKinds) {
        if (options == null) {
            return null;
        }
        for (String acceptedKind : acceptedKinds) {
            for (int i = 0; i < options.size(); i++) {
                Object item = options.get(i);
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject option = (JSONObject) item;
                if (acceptedKind.equals(option.optString("kind", ""))) {
                    String optionId = option.optString("optionId", "").trim();
                    if (!optionId.isEmpty()) {
                        return optionId;
                    }
                }
            }
        }
        return null;
    }

    private void sendMethodNotFound(Object id, String method) throws IOException {
        JSONObject error =
                object("code", -32601, "message", "Unsupported ACP client method: " + method);
        send(object("jsonrpc", JSONRPC_VERSION, "id", id, "error", error));
    }

    private synchronized void send(JSONObject message) throws IOException {
        writer.write(message.toString());
        writer.newLine();
        writer.flush();
    }

    private static boolean sameId(Object value, long expected) {
        return value != null && String.valueOf(expected).equals(String.valueOf(value));
    }

    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private static final class ApprovalDeniedException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
