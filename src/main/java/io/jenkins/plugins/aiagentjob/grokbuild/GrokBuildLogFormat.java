package io.jenkins.plugins.aiagentjob.grokbuild;

import io.jenkins.plugins.aiagentjob.AcpLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

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
}
