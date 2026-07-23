package io.jenkins.plugins.aiagentjob.claudecode;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Format-specific log classification for Claude Code stream-json output. Handles content arrays
 * (tool_use, tool_result, thinking, text) and stream_event deltas.
 *
 * <p>Used by both {@link ClaudeCodeAgentHandler} and {@link
 * io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler GeminiCliAgentHandler} since they
 * share the same stream-json format.
 */
public final class ClaudeCodeLogFormat implements AiAgentLogFormat {

    public static final ClaudeCodeLogFormat INSTANCE = new ClaudeCodeLogFormat();

    private ClaudeCodeLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        List<AiAgentLogParser.ParsedLine> parsed = classifyAll(lineNumber, json);
        return parsed == null || parsed.isEmpty() ? null : parsed.get(0);
    }

    @Override
    public List<AiAgentLogParser.ParsedLine> classifyAll(long lineNumber, JSONObject json) {
        String type = LogFormatUtils.firstNonEmpty(json, "type", "event", "kind", "subtype");
        String typeLower = LogFormatUtils.normalize(type);

        // assistant/user message with content array
        if (typeLower.equals("assistant") || typeLower.equals("user")) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONArray contentArr = message.optJSONArray("content");
                if (contentArr != null && contentArr.size() > 0) {
                    return classifyContentArrayAll(
                            lineNumber,
                            typeLower,
                            message.optString("id", ""),
                            contentArr,
                            json.toString(2));
                }
            }
        }

        // stream_event (streaming deltas)
        if (typeLower.equals("stream_event")) {
            JSONObject event = json.optJSONObject("event");
            if (event != null) {
                return List.of(classifyStreamEvent(lineNumber, event, json.toString(2)));
            }
        }

        // Not a Claude Code specific format — let fallback handle it
        return null;
    }

    public static AiAgentLogParser.ParsedLine classifyContentArray(
            long lineNumber, String parentType, JSONArray contentArr, String rawDetails) {
        return classifyContentArrayAll(lineNumber, parentType, "", contentArr, rawDetails).get(0);
    }

    private static List<AiAgentLogParser.ParsedLine> classifyContentArrayAll(
            long lineNumber,
            String parentType,
            String messageId,
            JSONArray contentArr,
            String rawDetails) {
        List<AiAgentLogParser.ParsedLine> parsed = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String category = parentType.equals("assistant") ? "assistant" : "user";
        int textStartIndex = -1;

        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            String ciType = LogFormatUtils.normalize(ci.optString("type"));

            if (ciType.equals("text")) {
                String value = ci.optString("text", "");
                if (!value.isEmpty()) {
                    if (textStartIndex < 0) textStartIndex = i;
                    if (text.length() > 0) text.append('\n');
                    text.append(value);
                }
                continue;
            }

            addPendingText(
                    parsed,
                    lineNumber,
                    category,
                    text,
                    rawDetails,
                    contentKey(messageId, textStartIndex, i - 1, "text"));
            textStartIndex = -1;

            if (ciType.equals("tool_use")) {
                String toolName = LogFormatUtils.firstNonEmpty(ci, "name");
                String toolCallId = LogFormatUtils.firstNonEmpty(ci, "id");
                String toolInput =
                        LogFormatUtils.extractToolInput(ci.optJSONObject("input"), toolName);
                parsed.add(
                        AiAgentLogParser.ParsedLine.toolCall(
                                        lineNumber, toolName, toolInput, rawDetails, toolCallId)
                                .withDeduplicationKey(
                                        toolCallId.isEmpty()
                                                ? contentKey(messageId, i, i, "tool-use")
                                                : "claude-tool-use:" + toolCallId));
            } else if (ciType.equals("tool_result")) {
                String toolCallId =
                        LogFormatUtils.firstNonEmpty(ci, "tool_use_id", "tool_call_id", "id");
                String toolName = LogFormatUtils.firstNonEmpty(ci, "tool_name", "name");
                String toolOutput = LogFormatUtils.extractToolResultContent(ci);
                if (!toolOutput.isEmpty()) {
                    parsed.add(
                            AiAgentLogParser.ParsedLine.toolResult(
                                            lineNumber,
                                            toolName,
                                            toolOutput,
                                            rawDetails,
                                            toolCallId)
                                    .withDeduplicationKey(
                                            toolCallId.isEmpty()
                                                    ? contentKey(messageId, i, i, "tool-result")
                                                    : "claude-tool-result:" + toolCallId));
                }
            } else if (ciType.equals("thinking")) {
                String thinking = LogFormatUtils.firstNonEmpty(ci, "thinking");
                if (!thinking.isEmpty()) {
                    parsed.add(
                            AiAgentLogParser.ParsedLine.thinking(lineNumber, thinking, rawDetails)
                                    .withDeduplicationKey(contentKey(messageId, i, i, "thinking")));
                }
            }
        }
        addPendingText(
                parsed,
                lineNumber,
                category,
                text,
                rawDetails,
                contentKey(messageId, textStartIndex, contentArr.size() - 1, "text"));
        if (parsed.isEmpty()) {
            parsed.add(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return parsed;
    }

    private static void addPendingText(
            List<AiAgentLogParser.ParsedLine> parsed,
            long lineNumber,
            String category,
            StringBuilder text,
            String rawDetails,
            String deduplicationKey) {
        if (text.length() == 0) return;
        parsed.add(
                AiAgentLogParser.ParsedLine.message(
                                lineNumber,
                                category,
                                LogFormatUtils.capitalize(category),
                                text.toString(),
                                rawDetails)
                        .withDeduplicationKey(deduplicationKey));
        text.setLength(0);
    }

    private static String contentKey(
            String messageId, int startIndex, int endIndex, String blockType) {
        if (messageId == null || messageId.isEmpty() || startIndex < 0) return null;
        return "claude-content:" + messageId + ':' + startIndex + ':' + endIndex + ':' + blockType;
    }

    public static AiAgentLogParser.ParsedLine classifyStreamEvent(
            long lineNumber, JSONObject event, String rawDetails) {
        String eventType = LogFormatUtils.normalize(event.optString("type"));

        if (eventType.equals("content_block_start") || eventType.equals("content_block_delta")) {
            JSONObject contentBlock = event.optJSONObject("content_block");
            JSONObject delta = event.optJSONObject("delta");
            JSONObject source = contentBlock != null ? contentBlock : delta;
            if (source != null) {
                String blockType = LogFormatUtils.normalize(source.optString("type"));
                if (blockType.contains("thinking")) {
                    String thinking = source.optString("thinking", source.optString("text", ""));
                    return thinking.isEmpty()
                            ? AiAgentLogParser.ParsedLine.raw(lineNumber, "")
                            : AiAgentLogParser.ParsedLine.thinking(
                                    lineNumber,
                                    thinking,
                                    rawDetails,
                                    eventType.equals("content_block_delta"));
                }
                if (blockType.contains("text")) {
                    String text = source.optString("text", "");
                    if (text.isEmpty()) {
                        return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
                    }
                    return AiAgentLogParser.ParsedLine.message(
                            lineNumber,
                            "assistant",
                            "Assistant",
                            text,
                            rawDetails,
                            eventType.equals("content_block_delta"));
                }
            }
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        if (eventType.equals("message_start")) {
            JSONObject message = event.optJSONObject("message");
            if (message != null) {
                String model = LogFormatUtils.firstNonEmpty(message, "model");
                if (!model.isEmpty()) {
                    return AiAgentLogParser.ParsedLine.system(
                            lineNumber, "System", "Model: " + model, rawDetails);
                }
            }
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        if (eventType.equals("content_block_stop")
                || eventType.equals("message_delta")
                || eventType.equals("message_stop")
                || eventType.equals("ping")) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.system(
                lineNumber, "Stream event", eventType, rawDetails);
    }
}
