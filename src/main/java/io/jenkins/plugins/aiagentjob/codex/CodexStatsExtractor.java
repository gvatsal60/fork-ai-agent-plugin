package io.jenkins.plugins.aiagentjob.codex;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;

import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Stats extractor for Codex CLI JSONL output. Codex reports usage in "turn.completed" events with a
 * nested "usage" object.
 */
public final class CodexStatsExtractor implements AiAgentStatsExtractor {

    public static final CodexStatsExtractor INSTANCE = new CodexStatsExtractor();

    private CodexStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);

        if ("turn.completed".equals(type)) {
            JSONObject usage = json.optJSONObject("usage");
            if (usage != null) {
                stats.accumulateUsage(usage);
                long input = usage.optLong("input_tokens", 0);
                long output = usage.optLong("output_tokens", 0);
                stats.addTotalTokens(AgentUsageStats.saturatedAdd(input, output));
            }
            return true;
        }

        return false;
    }
}
