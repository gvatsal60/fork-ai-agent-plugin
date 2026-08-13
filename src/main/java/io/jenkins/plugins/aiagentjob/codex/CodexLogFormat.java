package io.jenkins.plugins.aiagentjob.codex;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
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

        if ("error".equals(typeLower) || "turn.failed".equals(typeLower)) {
            JSONObject error = json.optJSONObject("error");
            String message = LogFormatUtils.firstNonEmpty(error, "message");
            if (message.isEmpty()) {
                message = LogFormatUtils.firstNonEmpty(json, "message", "error");
            }
            return message.isEmpty()
                    ? AiAgentLogParser.ParsedLine.raw(lineNumber, "")
                    : AiAgentLogParser.ParsedLine.result(
                            lineNumber, "error", "Error", message, json.toString(2));
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

        if (itemType.equals("error")) {
            String message = LogFormatUtils.firstNonEmpty(item, "message", "error");
            return message.isEmpty()
                    ? AiAgentLogParser.ParsedLine.raw(lineNumber, "")
                    : AiAgentLogParser.ParsedLine.result(
                            lineNumber, "error", "Error", message, rawDetails);
        }
        if (itemType.equals("todo_list")) {
            String todoList = formatTodoList(item.optJSONArray("items"));
            return todoList.isEmpty()
                    ? AiAgentLogParser.ParsedLine.raw(lineNumber, "")
                    : AiAgentLogParser.ParsedLine.system(lineNumber, "Plan", todoList, rawDetails);
        }
        if (itemType.equals("file_change")) {
            String changes = formatFileChanges(item.optJSONArray("changes"));
            if (changes.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            String itemId = LogFormatUtils.firstNonEmpty(item, "id");
            if (typeLower.contains("started") || status.contains("in_progress")) {
                return AiAgentLogParser.ParsedLine.toolCall(
                        lineNumber, "file_change", changes, rawDetails, itemId);
            }
            return AiAgentLogParser.ParsedLine.toolResult(
                    lineNumber,
                    "file_change",
                    changes,
                    LogFormatUtils.capitalize(status),
                    rawDetails,
                    itemId);
        }

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
                || itemType.contains("collab_tool_call")
                || itemType.contains("web_search")
                || itemType.contains("tool_call")
                || itemType.contains("tool")) {
            String toolCallId = LogFormatUtils.firstNonEmpty(item, "id", "call_id", "tool_call_id");
            String toolName = extractToolName(item, itemType);
            if (toolName.isEmpty() && itemType.contains("command_execution")) {
                toolName = "bash";
            }
            if (toolName.isEmpty() && itemType.contains("web_search")) {
                toolName = "web_search";
            }
            if (typeLower.contains("started") || status.contains("in_progress")) {
                String toolInput = extractToolInput(item);
                if (toolInput.isEmpty()) {
                    return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
                }
                return AiAgentLogParser.ParsedLine.toolCall(
                        lineNumber, toolName, toolInput, rawDetails, toolCallId);
            }
            String toolInput = itemType.contains("command_execution") ? extractToolInput(item) : "";
            String toolOutput = extractToolOutput(item);
            if (toolInput.isEmpty() && toolOutput.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
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
        if (text.isEmpty()) {
            text = LogFormatUtils.firstNonEmpty(item, "prompt");
        }
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
            output = LogFormatUtils.extractToolResultContent(result);
            if (!output.isEmpty()) return output;
            if (!result.isEmpty()) return result.toString(2);
        }

        JSONObject action = item.optJSONObject("action");
        if (action != null && !action.isEmpty()) {
            return action.toString(2);
        }
        JSONObject agentStates = item.optJSONObject("agents_states");
        if (agentStates != null && !agentStates.isEmpty()) {
            return agentStates.toString(2);
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
        if (toolName.isEmpty()) {
            toolName = LogFormatUtils.firstNonEmpty(item, "tool");
        }
        if (!toolName.isEmpty()) return toolName;
        if (itemType.contains("mcp")) return "mcp";
        return "";
    }

    static String formatFileChanges(JSONArray changes) {
        if (changes == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            JSONObject change = changes.optJSONObject(i);
            if (change == null) continue;
            String path = LogFormatUtils.firstNonEmpty(change, "path");
            String kind = LogFormatUtils.firstNonEmpty(change, "kind");
            if (path.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            if (!kind.isEmpty()) text.append(kind).append(' ');
            text.append(path);
        }
        return text.toString();
    }

    static String formatTodoList(JSONArray items) {
        if (items == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String itemText = LogFormatUtils.firstNonEmpty(item, "text");
            if (itemText.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(item.optBoolean("completed", false) ? "[x] " : "[ ] ").append(itemText);
        }
        return text.toString();
    }
}
