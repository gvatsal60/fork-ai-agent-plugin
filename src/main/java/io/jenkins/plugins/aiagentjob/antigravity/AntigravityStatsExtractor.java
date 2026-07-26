package io.jenkins.plugins.aiagentjob.antigravity;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONObject;

/** Extracts model, token, duration, turn, and tool statistics from Antigravity CLI JSONL. */
public final class AntigravityStatsExtractor implements AiAgentStatsExtractor {
    public static final AntigravityStatsExtractor INSTANCE = new AntigravityStatsExtractor();

    private AntigravityStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        String event = LogFormatUtils.normalize(json.optString("event", ""));
        if ("init".equals(event)) {
            JSONObject init = json.optJSONObject("init");
            stats.setDetectedModelIfEmpty(LogFormatUtils.firstNonEmpty(init, "model"));
            return true;
        }
        if ("step_update".equals(event)) {
            extractStepUpdate(json.optJSONObject("step_update"), stats);
            return true;
        }
        if ("result".equals(event)) {
            extractResult(json.optJSONObject("result"), stats);
            return true;
        }
        return false;
    }

    private static void extractStepUpdate(JSONObject update, AgentUsageStats stats) {
        if (update == null) {
            return;
        }
        String state = LogFormatUtils.normalize(update.optString("state"));
        boolean terminal = "done".equals(state) || AntigravityLogFormat.isFailureState(state);
        if (!terminal) {
            return;
        }
        JSONObject usage = update.optJSONObject("usage");
        if (usage != null) {
            accumulateUsage(usage, stats);
        }
        if ("tool".equals(LogFormatUtils.normalize(update.optString("step_type")))) {
            stats.recordToolCall(AntigravityLogFormat.stepId(update));
        }
    }

    private static void extractResult(JSONObject result, AgentUsageStats stats) {
        if (result == null) {
            return;
        }
        JSONObject usage = result.optJSONObject("usage");
        if (usage != null) {
            setUsageTotals(usage, stats);
        }
        stats.addDurationMs(secondsToMillis(result.optDouble("duration_seconds", 0)));
        stats.addNumTurns(result.optInt("num_turns", 0));
    }

    private static void accumulateUsage(JSONObject usage, AgentUsageStats stats) {
        stats.incrementInputTokens(usage.optLong("input_tokens", 0));
        stats.incrementOutputTokens(usage.optLong("output_tokens", 0));
        stats.incrementReasoningTokens(usage.optLong("thinking_tokens", 0));
        stats.incrementCacheReadTokens(usage.optLong("cache_read_tokens", 0));
        stats.incrementTotalTokens(usage.optLong("total_tokens", 0));
    }

    private static void setUsageTotals(JSONObject usage, AgentUsageStats stats) {
        stats.addInputTokens(usage.optLong("input_tokens", 0));
        stats.addOutputTokens(usage.optLong("output_tokens", 0));
        stats.addReasoningTokens(usage.optLong("thinking_tokens", 0));
        stats.addCacheReadTokens(usage.optLong("cache_read_tokens", 0));
        stats.addTotalTokens(usage.optLong("total_tokens", 0));
    }

    private static long secondsToMillis(double seconds) {
        if (seconds <= 0 || !Double.isFinite(seconds)) {
            return 0;
        }
        if (seconds >= Long.MAX_VALUE / 1000.0) {
            return Long.MAX_VALUE;
        }
        return Math.round(seconds * 1000);
    }
}
