package io.jenkins.plugins.aiagentjob.grokbuild;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;

import net.sf.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/** Extracts Grok Build headless and ACP token, turn, model, duration, and cost metadata. */
public final class GrokBuildStatsExtractor implements AiAgentStatsExtractor {

    private static final double USD_TICKS_PER_DOLLAR = 10_000_000_000.0;

    public static final GrokBuildStatsExtractor INSTANCE = new GrokBuildStatsExtractor();

    private GrokBuildStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);
        if ("end".equals(type) || "error".equals(type) || "max_turns_reached".equals(type)) {
            extractHeadless(json, stats);
            return true;
        }

        JSONObject result = json.optJSONObject("result");
        JSONObject metadata = result == null ? null : result.optJSONObject("_meta");
        JSONObject usage = metadata == null ? null : metadata.optJSONObject("usage");
        if (usage == null) {
            return false;
        }

        long cachedInput = usage.optLong("cachedReadTokens", 0);
        long cacheCreation = usage.optLong("cacheCreationTokens", 0);
        long fullInput = usage.optLong("inputTokens", 0);
        stats.addInputTokens(Math.max(0, fullInput - cachedInput - cacheCreation));
        stats.addCacheReadTokens(cachedInput);
        stats.addCacheWriteTokens(cacheCreation);
        stats.addOutputTokens(usage.optLong("outputTokens", 0));
        stats.addReasoningTokens(usage.optLong("reasoningTokens", 0));
        stats.addTotalTokens(usage.optLong("totalTokens", 0));
        stats.addApiDurationMs(usage.optLong("apiDurationMs", 0));
        stats.addNumTurns(usage.optInt("numTurns", 0));
        addCostFromTicks(usage.optLong("costUsdTicks", 0), stats);

        String model = selectModel(usage.optJSONObject("modelUsage"));
        if (model.isEmpty()) {
            model = metadata.optString("modelId", "").trim();
        }
        stats.setDetectedModelIfEmpty(model);
        return true;
    }

    private static void extractHeadless(JSONObject json, AgentUsageStats stats) {
        JSONObject usage = json.optJSONObject("usage");
        if (usage != null) {
            stats.addInputTokens(usage.optLong("input_tokens", 0));
            stats.addCacheReadTokens(usage.optLong("cache_read_input_tokens", 0));
            stats.addCacheWriteTokens(usage.optLong("cache_creation_input_tokens", 0));
            stats.addOutputTokens(usage.optLong("output_tokens", 0));
            stats.addReasoningTokens(usage.optLong("reasoning_tokens", 0));
            stats.addTotalTokens(usage.optLong("total_tokens", 0));
        }
        stats.addNumTurns(json.optInt("num_turns", 0));

        double cost = json.optDouble("total_cost_usd", 0);
        if (Double.isFinite(cost) && cost > 0) {
            stats.addCostUsd(cost);
        } else {
            addCostFromTicks(json.optLong("total_cost_usd_ticks", 0), stats);
        }
        stats.setDetectedModelIfEmpty(selectModel(json.optJSONObject("modelUsage")));
    }

    private static void addCostFromTicks(long ticks, AgentUsageStats stats) {
        if (ticks > 0) {
            stats.addCostUsd(ticks / USD_TICKS_PER_DOLLAR);
        }
    }

    private static String selectModel(JSONObject modelUsage) {
        if (modelUsage == null || modelUsage.isEmpty()) {
            return "";
        }
        String selected = "";
        long selectedCalls = -1;
        Iterator<?> keys = modelUsage.keys();
        while (keys.hasNext()) {
            String model = String.valueOf(keys.next());
            JSONObject usage = modelUsage.optJSONObject(model);
            long calls = usage == null ? 0 : usage.optLong("modelCalls", 0);
            if (calls > selectedCalls
                    || (calls == selectedCalls
                            && (selected.isEmpty() || model.compareTo(selected) < 0))) {
                selected = model;
                selectedCalls = calls;
            }
        }
        return selected;
    }
}
