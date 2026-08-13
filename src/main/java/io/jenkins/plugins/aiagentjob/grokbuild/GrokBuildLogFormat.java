package io.jenkins.plugins.aiagentjob.grokbuild;

import io.jenkins.plugins.aiagentjob.AcpLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Classifies Grok Build streaming JSON and Agent Client Protocol output. */
public final class GrokBuildLogFormat implements AiAgentLogFormat {

    public static final GrokBuildLogFormat INSTANCE = new GrokBuildLogFormat();

    private GrokBuildLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        AiAgentLogParser.ParsedLine acpLine = AcpLogFormat.INSTANCE.classify(lineNumber, json);
        if (acpLine != null) {
            return acpLine;
        }

        String type = LogFormatUtils.normalize(json.optString("type", ""));
        String rawDetails = json.toString(2);
        if ("text".equals(type)) {
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "assistant", "Assistant", streamingData(json), rawDetails, true);
        }
        if ("thought".equals(type)) {
            return AiAgentLogParser.ParsedLine.thinking(
                    lineNumber, streamingData(json), rawDetails, true);
        }
        if ("tool_call".equals(type)) {
            String toolName = LogFormatUtils.firstNonEmpty(json, "toolName", "title");
            String toolCallId = LogFormatUtils.firstNonEmpty(json, "toolCallId");
            String input = displayValue(json.opt("rawInput"), toolName);
            if (input.isEmpty()) input = displayValue(json.opt("content"), toolName);
            return AiAgentLogParser.ParsedLine.toolCall(
                    lineNumber, toolName, input, rawDetails, toolCallId);
        }
        if ("tool_call_update".equals(type)) {
            String toolCallId = LogFormatUtils.firstNonEmpty(json, "toolCallId");
            JSONObject rawOutput = json.optJSONObject("rawOutput");
            String toolName = LogFormatUtils.firstNonEmpty(rawOutput, "type", "toolName", "name");
            if (toolName.isEmpty()) toolName = "Tool";
            String output = displayValue(json.opt("content"), "");
            if (output.isEmpty()) output = displayValue(rawOutput, "");
            if (output.isEmpty()) {
                output = LogFormatUtils.capitalize(LogFormatUtils.firstNonEmpty(json, "status"));
            }
            return AiAgentLogParser.ParsedLine.toolResult(
                    lineNumber, toolName, output, rawDetails, toolCallId);
        }
        if ("plan".equals(type)) {
            String plan = displayValue(json.opt("entries"), "");
            return plan.isEmpty()
                    ? AiAgentLogParser.ParsedLine.raw(lineNumber, "")
                    : AiAgentLogParser.ParsedLine.system(lineNumber, "Plan", plan, rawDetails);
        }
        if (type.startsWith("auto_compact_") || type.startsWith("auto_continue_")) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        if ("max_turns_reached".equals(type)) {
            return AiAgentLogParser.ParsedLine.result(
                    lineNumber, "result", "Result", "Max turns reached", rawDetails);
        }
        if ("end".equals(type)) {
            String stopReason = LogFormatUtils.firstNonEmpty(json, "stopReason", "stop_reason");
            return AiAgentLogParser.ParsedLine.result(
                    lineNumber,
                    "result",
                    "Result",
                    LogFormatUtils.capitalize(stopReason),
                    rawDetails);
        }
        if ("error".equals(type)) {
            String message = LogFormatUtils.firstNonEmpty(json, "message", "error");
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "error", "Error", message, rawDetails);
        }
        return null;
    }

    private static String streamingData(JSONObject json) {
        Object data = json.opt("data");
        return data instanceof String ? (String) data : "";
    }

    private static String displayValue(Object value, String toolName) {
        if (value instanceof String) return (String) value;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String direct =
                    LogFormatUtils.firstNonEmpty(
                            object,
                            "output",
                            "output_for_prompt",
                            "text",
                            "result",
                            "content",
                            "stdout",
                            "stderr");
            if (!direct.isEmpty()) return direct;
            String text = LogFormatUtils.extractToolInput(object, toolName);
            return text.isEmpty() ? object.toString(2) : text;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            String text = LogFormatUtils.joinTextArray(array);
            if (text.isEmpty()) text = nestedContent(array);
            return text.isEmpty() ? array.toString(2) : text;
        }
        return "";
    }

    private static String nestedContent(JSONArray array) {
        StringBuilder text = new StringBuilder();
        for (Object value : array) {
            if (!(value instanceof JSONObject)) continue;
            JSONObject item = (JSONObject) value;
            JSONObject content = item.optJSONObject("content");
            String itemText = LogFormatUtils.extractText(content == null ? item : content);
            if (itemText.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(itemText);
        }
        return text.toString();
    }
}
