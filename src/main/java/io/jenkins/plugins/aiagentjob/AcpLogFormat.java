package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Classifies standard Agent Client Protocol messages shared by ACP-capable handlers. */
public final class AcpLogFormat implements AiAgentLogFormat {

    public static final AcpLogFormat INSTANCE = new AcpLogFormat();

    private AcpLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        String method = json.optString("method", "");
        String rawDetails = json.toString(2);
        if ("session/request_permission".equals(method)) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        if ("session/update".equals(method)) {
            JSONObject params = json.optJSONObject("params");
            JSONObject update = params == null ? null : params.optJSONObject("update");
            if (update == null) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return classifyUpdate(lineNumber, update, rawDetails);
        }

        JSONObject result = json.optJSONObject("result");
        String stopReason = LogFormatUtils.firstNonEmpty(result, "stopReason", "stop_reason");
        if (!stopReason.isEmpty()) {
            return AiAgentLogParser.ParsedLine.result(
                    lineNumber,
                    "result",
                    "Result",
                    LogFormatUtils.capitalize(stopReason),
                    rawDetails);
        }
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            String message = LogFormatUtils.firstNonEmpty(error, "message");
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "error", "Error", message, rawDetails);
        }
        if (json.has("id") && json.has("result")) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return null;
    }

    private static AiAgentLogParser.ParsedLine classifyUpdate(
            long lineNumber, JSONObject update, String rawDetails) {
        String updateType = LogFormatUtils.normalize(update.optString("sessionUpdate", ""));
        JSONObject content = update.optJSONObject("content");
        String text = extractChunkText(content);

        if ("agent_message_chunk".equals(updateType)) {
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "assistant", "Assistant", text, rawDetails, true);
        }
        if ("user_message_chunk".equals(updateType)) {
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "user", "User", text, rawDetails);
        }
        if ("agent_thought_chunk".equals(updateType)) {
            return AiAgentLogParser.ParsedLine.thinking(lineNumber, text, rawDetails, true);
        }
        if ("tool_call".equals(updateType)) {
            return classifyToolCall(lineNumber, update, rawDetails);
        }
        if ("tool_call_update".equals(updateType)) {
            return classifyToolUpdate(lineNumber, update, rawDetails);
        }
        return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
    }

    private static String extractChunkText(JSONObject content) {
        if (content == null) {
            return "";
        }
        Object text = content.opt("text");
        if (text instanceof String) {
            return (String) text;
        }
        return LogFormatUtils.extractText(content);
    }

    private static AiAgentLogParser.ParsedLine classifyToolCall(
            long lineNumber, JSONObject update, String rawDetails) {
        String toolName = LogFormatUtils.firstNonEmpty(update, "kind", "title");
        String toolCallId =
                LogFormatUtils.firstNonEmpty(update, "toolCallId", "tool_call_id", "id");
        String input = LogFormatUtils.extractToolInput(update.optJSONObject("rawInput"), toolName);
        if (input.isEmpty()) {
            input = LogFormatUtils.firstNonEmpty(update, "title");
        }
        if (input.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.toolCall(
                lineNumber, toolName, input, rawDetails, toolCallId);
    }

    private static AiAgentLogParser.ParsedLine classifyToolUpdate(
            long lineNumber, JSONObject update, String rawDetails) {
        String status = LogFormatUtils.normalize(update.optString("status", ""));
        if (!"completed".equals(status) && !"failed".equals(status)) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }

        String toolName = LogFormatUtils.firstNonEmpty(update, "kind", "title");
        String toolCallId =
                LogFormatUtils.firstNonEmpty(update, "toolCallId", "tool_call_id", "id");
        String output = extractToolOutput(update);
        if (output.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.toolResult(
                lineNumber, toolName, output, rawDetails, toolCallId);
    }

    private static String extractToolOutput(JSONObject update) {
        JSONArray content = update.optJSONArray("content");
        if (content != null) {
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                Object item = content.get(i);
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject nestedContent = ((JSONObject) item).optJSONObject("content");
                String text = extractChunkText(nestedContent);
                if (!text.isEmpty()) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(text);
                }
            }
            if (output.length() > 0) {
                return output.toString();
            }
        }

        Object rawOutput = update.opt("rawOutput");
        if (rawOutput instanceof String) {
            return (String) rawOutput;
        }
        if (rawOutput instanceof JSONObject && !((JSONObject) rawOutput).isEmpty()) {
            return ((JSONObject) rawOutput).toString(2);
        }
        return "";
    }
}
