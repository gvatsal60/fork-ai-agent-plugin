package io.jenkins.plugins.aiagentjob.codex;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONObject;

/**
 * Format-specific log classification for Codex CLI JSONL output. Handles item.started /
 * item.completed wrappers for reasoning, agent_message, and command_execution items.
 */
public final class CodexLogFormat implements AiAgentLogFormat {

    public static final CodexLogFormat INSTANCE = new CodexLogFormat();

    private CodexLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        String type = LogFormatUtils.firstNonEmpty(json, "type", "event", "kind", "subtype");
        String typeLower = LogFormatUtils.normalize(type);

        // Codex wraps events in an "item" object
        JSONObject item = json.optJSONObject("item");
        if (item != null) {
            return classifyItem(lineNumber, typeLower, item, json.toString(2));
        }

        // Codex thread/turn lifecycle events
        if (typeLower.startsWith("thread.") || typeLower.startsWith("turn.")) {
            String text = LogFormatUtils.extractText(json);
            if (text.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return AiAgentLogParser.ParsedLine.system(lineNumber, "System", text, json.toString(2));
        }

        return null;
    }

    static AiAgentLogParser.ParsedLine classifyItem(
            long lineNumber, String typeLower, JSONObject item, String rawDetails) {
        String itemType = LogFormatUtils.normalize(item.optString("type"));
        String status = LogFormatUtils.normalize(item.optString("status"));

        if (itemType.contains("reason")) {
            String itemText = LogFormatUtils.extractText(item);
            if (itemText.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return AiAgentLogParser.ParsedLine.thinking(lineNumber, itemText, rawDetails);
        }
        if (itemType.contains("agent_message") || itemType.contains("message")) {
            String itemText = LogFormatUtils.extractText(item);
            if (itemText.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "assistant", "Assistant", itemText, rawDetails);
        }
        if (itemType.contains("command_execution")
                || itemType.contains("mcp_tool_call")
                || itemType.contains("tool_call")
                || itemType.contains("tool")) {
            String toolCallId = LogFormatUtils.firstNonEmpty(item, "id", "call_id", "tool_call_id");
            String toolName = extractToolName(item, itemType);
            if (toolName.isEmpty() && itemType.contains("command_execution")) {
                toolName = "bash";
            }
            if (typeLower.contains("started") || status.contains("in_progress")) {
                String toolInput = extractToolInput(item);
                if (toolInput.isEmpty()) {
                    return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
                }
                return AiAgentLogParser.ParsedLine.toolCall(
                        lineNumber, toolName, toolInput, rawDetails, toolCallId);
            }
            String toolOutput = extractToolOutput(item);
            if (toolOutput.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            String toolInput = itemType.contains("command_execution") ? extractToolInput(item) : "";
            return AiAgentLogParser.ParsedLine.toolResult(
                    lineNumber, toolName, toolInput, toolOutput, rawDetails, toolCallId);
        }
        String itemText = LogFormatUtils.extractText(item);
        if (itemText.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.system(lineNumber, "System", itemText, rawDetails);
    }

    static String extractToolInput(JSONObject item) {
        String command = LogFormatUtils.firstNonEmpty(item, "command");
        if (!command.isEmpty()) return command;

        JSONObject parameters = item.optJSONObject("parameters");
        if (parameters != null) {
            String parameterText =
                    LogFormatUtils.extractToolInput(
                            parameters, LogFormatUtils.firstNonEmpty(item, "name"));
            if (!parameterText.isEmpty()) return parameterText;
        }

        JSONObject arguments = item.optJSONObject("arguments");
        if (arguments != null) {
            String argumentText =
                    LogFormatUtils.extractToolInput(
                            arguments, LogFormatUtils.firstNonEmpty(item, "name"));
            if (!argumentText.isEmpty()) return argumentText;
            return arguments.toString(2);
        }

        String text = LogFormatUtils.firstNonEmpty(item, "input", "query", "path", "url");
        if (!text.isEmpty()) return text;
        return LogFormatUtils.extractText(item);
    }

    static String extractToolOutput(JSONObject item) {
        String output =
                LogFormatUtils.firstNonEmpty(
                        item, "aggregated_output", "output", "stdout", "stderr");
        if (!output.isEmpty()) return output;

        JSONObject result = item.optJSONObject("result");
        if (result != null) {
            output =
                    LogFormatUtils.firstNonEmpty(
                            result, "output", "stdout", "stderr", "text", "result");
            if (!output.isEmpty()) return output;
            if (!result.isEmpty()) return result.toString(2);
        }

        if (item.containsKey("exit_code")) {
            int exitCode = item.optInt("exit_code");
            if (exitCode != 0) {
                return "Exit code: " + exitCode;
            }
        }
        return "";
    }

    static String extractToolName(JSONObject item, String itemType) {
        String toolName = LogFormatUtils.firstNonEmpty(item, "tool_name", "toolName", "name");
        if (!toolName.isEmpty()) return toolName;
        if (itemType.contains("mcp")) return "mcp";
        return "";
    }
}
