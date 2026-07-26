package io.jenkins.plugins.aiagentjob.grokbuild;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class GrokBuildAgentHandlerTest {

    @TempDir Path tempDirectory;

    @Test
    void resolvesTerminalFailureEventsToNonZeroExit() throws Exception {
        GrokBuildAgentHandler handler = new GrokBuildAgentHandler();
        List<Map.Entry<String, String>> failureEvents =
                List.of(
                        Map.entry(
                                "cancelled",
                                "{\"type\":\"end\",\"stopReason\":\"Cancelled\",\"usage\":{\"inputTokens\":1630,\"outputTokens\":32}}"),
                        Map.entry("max-turns", "{\"type\":\"max_turns_reached\",\"maxTurns\":4}"),
                        Map.entry("error", "{\"type\":\"error\",\"message\":\"request failed\"}"));

        for (Map.Entry<String, String> failureEvent : failureEvents) {
            Path rawLog = tempDirectory.resolve(failureEvent.getKey() + ".jsonl");
            Files.writeString(rawLog, failureEvent.getValue() + "\n");

            assertEquals(1, handler.resolveExitCode(0, rawLog.toFile()), failureEvent.getKey());
        }
    }
}
