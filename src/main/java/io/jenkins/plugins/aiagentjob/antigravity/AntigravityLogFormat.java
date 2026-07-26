package io.jenkins.plugins.aiagentjob.antigravity;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Classifies Antigravity CLI {@code stream-json} events. */
public final class AntigravityLogFormat implements AiAgentLogFormat {
    public static final AntigravityLogFormat INSTANCE = new AntigravityLogFormat();

    private AntigravityLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        List<AiAgentLogParser.ParsedLine> parsed = classifyAll(lineNumber, json);
        return parsed == null || parsed.isEmpty() ? null : parsed.get(0);
    }

    @Override
    public List<AiAgentLogParser.ParsedLine> classifyAll(long lineNumber, JSONObject json) {
        String event = LogFormatUtils.normalize(json.optString("event", ""));
        String rawDetails = json.toString(2);
        if ("init".equals(event)) {
            return List.of(classifyInit(lineNumber, json.optJSONObject("init"), rawDetails));
        }
        if ("step_update".equals(event)) {
            return List.of(
                    classifyStepUpdate(lineNumber, json.optJSONObject("step_update"), rawDetails));
        }
        if ("result".equals(event)) {
            return List.of(classifyResult(lineNumber, json.optJSONObject("result"), rawDetails));
        }
        return null;
    }

    private static AiAgentLogParser.ParsedLine classifyInit(
            long lineNumber, JSONObject init, String rawDetails) {
        String model = LogFormatUtils.firstNonEmpty(init, "model");
        if (model.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.system(
                lineNumber, "System", "Model: " + model, rawDetails);
    }

    private static AiAgentLogParser.ParsedLine classifyStepUpdate(
            long lineNumber, JSONObject update, String rawDetails) {
        if (update == null) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        String stepType = LogFormatUtils.normalize(update.optString("step_type", ""));
        String stepId = stepId(update);
        if ("agent_response".equals(stepType)) {
            String text = LogFormatUtils.firstNonEmpty(update, "text_delta", "text");
            if (text.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return AiAgentLogParser.ParsedLine.message(
                            lineNumber, "assistant", "Assistant", text, rawDetails, true)
                    .withDeduplicationKey("antigravity-response:" + stepId);
        }
        if ("tool".equals(stepType)) {
            return classifyToolUpdate(lineNumber, update, stepId, rawDetails);
        }
        return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
    }

    private static AiAgentLogParser.ParsedLine classifyToolUpdate(
            long lineNumber, JSONObject update, String stepId, String rawDetails) {
        JSONObject toolInfo = update.optJSONObject("tool_info");
        if (toolInfo == null) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        String toolName = LogFormatUtils.firstNonEmpty(update, "tool_name");
        if (toolName.isEmpty()) {
            toolName = LogFormatUtils.firstNonEmpty(toolInfo, "name");
        }
        String state = LogFormatUtils.normalize(update.optString("state", ""));
        if ("active".equals(state)) {
            JSONObject parameters = toolInfo.optJSONObject("parameters");
            String input = LogFormatUtils.firstNonEmpty(parameters, "CommandLine", "command_line");
            if (input.isEmpty()) {
                input = LogFormatUtils.extractToolInput(parameters, toolName);
            }
            return AiAgentLogParser.ParsedLine.toolCall(
                            lineNumber, toolName, input, rawDetails, stepId)
                    .withDeduplicationKey("antigravity-tool-call:" + stepId);
        }

        String output = LogFormatUtils.firstNonEmpty(toolInfo, "output");
        if (output.isEmpty()) {
            output = errorMessage(toolInfo);
        }
        if (output.isEmpty()) {
            output = errorMessage(update);
        }
        if (output.isEmpty() && isFailureState(state)) {
            output = "Tool " + state;
        }
        if (output.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.toolResult(
                        lineNumber, toolName, output, rawDetails, stepId)
                .withDeduplicationKey("antigravity-tool-result:" + stepId);
    }

    private static AiAgentLogParser.ParsedLine classifyResult(
            long lineNumber, JSONObject result, String rawDetails) {
        if (result == null) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        String status = LogFormatUtils.firstNonEmpty(result, "status");
        String error = errorMessage(result);
        String response = LogFormatUtils.firstNonEmpty(result, "response");
        boolean failed = !error.isEmpty() || isFailureState(LogFormatUtils.normalize(status));
        String content =
                !error.isEmpty()
                        ? error
                        : !response.isEmpty()
                                ? response
                                : LogFormatUtils.capitalize(status.toLowerCase(Locale.ROOT));
        if (content.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        String label = failed ? "Error" : "Result";
        double durationSeconds = result.optDouble("duration_seconds", 0);
        if (durationSeconds > 0 && Double.isFinite(durationSeconds)) {
            label += String.format(Locale.US, " (%.1fs)", durationSeconds);
        }
        return AiAgentLogParser.ParsedLine.result(
                lineNumber, failed ? "error" : "result", label, content, rawDetails);
    }

    static String stepId(JSONObject update) {
        String conversationId = LogFormatUtils.firstNonEmpty(update, "conversation_id");
        String stepIndex = LogFormatUtils.firstNonEmpty(update, "step_index");
        if (conversationId.isEmpty() || stepIndex.isEmpty()) {
            return "";
        }
        return conversationId + ':' + stepIndex;
    }

    static String errorMessage(JSONObject json) {
        String error = LogFormatUtils.firstNonEmpty(json, "error");
        if (!error.isEmpty() || json == null) {
            return error;
        }
        JSONObject nested = json.optJSONObject("error");
        return LogFormatUtils.firstNonEmpty(nested, "message", "reason", "details", "error");
    }

    static boolean isFailureState(String state) {
        return "error".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }
}
