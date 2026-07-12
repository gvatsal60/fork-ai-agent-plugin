package io.jenkins.plugins.aiagentjob.opencode;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;

import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Stats extractor for OpenCode output. Run mode reports additive per-step usage; ACP mode reports
 * cumulative context and cost updates.
 */
public final class OpenCodeStatsExtractor implements AiAgentStatsExtractor {

    public static final OpenCodeStatsExtractor INSTANCE = new OpenCodeStatsExtractor();

    private OpenCodeStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        if ("session/update".equals(json.optString("method", ""))) {
            JSONObject params = json.optJSONObject("params");
            JSONObject update = params == null ? null : params.optJSONObject("update");
            if (update != null && "usage_update".equals(update.optString("sessionUpdate", ""))) {
                long used = update.optLong("used", 0);
                stats.addInputTokens(used);
                stats.addTotalTokens(used);
                JSONObject cost = update.optJSONObject("cost");
                if (cost != null && "USD".equalsIgnoreCase(cost.optString("currency", ""))) {
                    stats.addCostUsd(cost.optDouble("amount", 0));
                }
                return true;
            }
        }

        String type = json.optString("type", "").toLowerCase(Locale.ROOT);

        if ("step_finish".equals(type)) {
            JSONObject part = json.optJSONObject("part");
            if (part != null) {
                extractOpenCodePart(part, stats);
            }
            return true;
        }

        return false;
    }

    private void extractOpenCodePart(JSONObject part, AgentUsageStats stats) {
        double partCost = part.optDouble("cost", 0);
        if (partCost > 0) stats.incrementCostUsd(partCost);

        JSONObject tokens = part.optJSONObject("tokens");
        if (tokens == null) return;

        stats.incrementInputTokens(tokens.optLong("input", 0));
        stats.incrementOutputTokens(tokens.optLong("output", 0));
        stats.incrementReasoningTokens(tokens.optLong("reasoning", 0));
        long partTotal = tokens.optLong("total", 0);
        if (partTotal > 0) stats.incrementTotalTokens(partTotal);

        JSONObject cache = tokens.optJSONObject("cache");
        if (cache != null) {
            stats.incrementCacheReadTokens(cache.optLong("read", 0));
            stats.incrementCacheWriteTokens(cache.optLong("write", 0));
        }
    }
}
