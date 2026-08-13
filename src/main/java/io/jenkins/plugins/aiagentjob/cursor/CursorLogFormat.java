package io.jenkins.plugins.aiagentjob.cursor;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONObject;

/**
 * Format-specific log classification for Cursor Agent stream-json output. Handles tool_call events
 * with started/completed subtypes and various tool call shapes (shell, read, write, etc.).
 */
public final class CursorLogFormat implements AiAgentLogFormat {

    public static final CursorLogFormat INSTANCE = new CursorLogFormat();

    private CursorLogFormat() {}

    private static final String[] TOOL_CALL_KEYS = {
        "shellToolCall", "readToolCall", "writeToolCall", "editToolCall",
        "globToolCall", "grepToolCall", "lsToolCall", "deleteToolCall",
        "mcpToolCall", "semSearchToolCall"
    };

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        String type = LogFormatUtils.firstNonEmpty(json, "type", "event", "kind", "subtype");
        String typeLower = LogFormatUtils.normalize(type);

        // Cursor emits "thinking" events
        if (typeLower.equals("thinking")) {
            String thinkText = LogFormatUtils.firstNonEmpty(json, "text");
            return AiAgentLogParser.ParsedLine.thinking(lineNumber, thinkText, json.toString(2));
        }

        // Cursor-specific tool_call with subtype started/completed
        if (typeLower.equals("tool_call")) {
            return classifyToolCall(lineNumber, json, json.toString(2));
        }

        return null;
    }

    static AiAgentLogParser.ParsedLine classifyToolCall(
            long lineNumber, JSONObject json, String rawDetails) {
        String subtype = LogFormatUtils.normalize(LogFormatUtils.firstNonEmpty(json, "subtype"));
        String callId = LogFormatUtils.firstNonEmpty(json, "call_id", "tool_call_id", "tool_id");
        String toolName = extractToolName(json);
        JSONObject tc = json.optJSONObject("tool_call");

        if (tc == null) {
            if (toolName.isEmpty()) {
                toolName = LogFormatUtils.firstNonEmpty(json, "tool_name", "toolName", "name");
            }
            JSONObject parameters = json.optJSONObject("parameters");
            String input = LogFormatUtils.extractToolInput(parameters, toolName);
            if (input.isEmpty()) {
                input = LogFormatUtils.firstNonEmpty(json, "text", "input", "command");
            }
            if (input.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return AiAgentLogParser.ParsedLine.toolCall(
                    lineNumber, toolName, input, rawDetails, callId);
        }

        if (subtype.equals("completed")) {
            String output = extractToolOutput(tc, toolName);
            return AiAgentLogParser.ParsedLine.toolResult(
                    lineNumber, toolName, output, rawDetails, callId);
        }
        String input = extractToolInput(tc, toolName);
        return AiAgentLogParser.ParsedLine.toolCall(
                lineNumber, toolName, input, rawDetails, callId);
    }

    static String extractToolInput(JSONObject tc, String toolName) {
        if (tc == null) return "";
        for (String key : TOOL_CALL_KEYS) {
            JSONObject call = tc.optJSONObject(key);
            if (call == null) continue;
            JSONObject args = call.optJSONObject("args");
            if (args == null) continue;
            String cmd = LogFormatUtils.firstNonEmpty(args, "command");
            if (!cmd.isEmpty()) return cmd;
            String path = LogFormatUtils.firstNonEmpty(args, "path", "file_path");
            if (!path.isEmpty()) return path;
            String pattern = LogFormatUtils.firstNonEmpty(args, "pattern", "glob");
            if (!pattern.isEmpty()) return pattern;
            return args.toString(2);
        }
        return "";
    }

    static String extractToolOutput(JSONObject tc, String toolName) {
        if (tc == null) return "";
        for (String key : TOOL_CALL_KEYS) {
            JSONObject call = tc.optJSONObject(key);
            if (call == null) continue;
            Object resultObj = call.opt("result");
            if (resultObj instanceof String) return (String) resultObj;
            if (resultObj instanceof JSONObject) {
                JSONObject result = (JSONObject) resultObj;
                JSONObject success = result.optJSONObject("success");
                if (success != null) {
                    String content = success.optString("content", "");
                    if (!content.isEmpty()) return content;
                    String stdout = success.optString("stdout", "");
                    String stderr = success.optString("stderr", "");
                    if (!stdout.isEmpty()) return stdout;
                    if (!stderr.isEmpty()) return stderr;
                }
                return result.toString(2);
            }
        }
        return "";
    }

    static String extractToolName(JSONObject json) {
        JSONObject tc = json.optJSONObject("tool_call");
        if (tc == null) return "";
        for (String key : TOOL_CALL_KEYS) {
            if (tc.has(key)) {
                return key.replace("ToolCall", "").replace("Tool", "");
            }
        }
        return LogFormatUtils.firstNonEmpty(tc, "name", "tool_name");
    }
}
