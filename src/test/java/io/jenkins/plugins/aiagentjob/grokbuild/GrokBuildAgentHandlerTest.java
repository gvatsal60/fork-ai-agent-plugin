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
                                "{\"type\":\"end\",\"stopReason\":\"Cancelled\",\"usage\":{\"inputTokens\":10,\"outputTokens\":2}}"),
                        Map.entry("max-turns", "{\"type\":\"max_turns_reached\",\"maxTurns\":4}"),
                        Map.entry(
                                "acp-max-tokens",
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"max_tokens\"}}"),
                        Map.entry("error", "{\"type\":\"error\",\"message\":\"request failed\"}"));

        for (Map.Entry<String, String> failureEvent : failureEvents) {
            Path rawLog = tempDirectory.resolve(failureEvent.getKey() + ".jsonl");
            Files.writeString(rawLog, failureEvent.getValue() + "\n");

            assertEquals(1, handler.resolveExitCode(0, rawLog.toFile()), failureEvent.getKey());
        }
    }

    @Test
    void preservesSuccessfulAndNonZeroProcessExits() throws Exception {
        GrokBuildAgentHandler handler = new GrokBuildAgentHandler();
        Path rawLog = tempDirectory.resolve("success.jsonl");
        Files.writeString(
                rawLog,
                "{\"type\":\"end\",\"stopReason\":\"EndTurn\"}\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}\n");

        assertEquals(0, handler.resolveExitCode(0, rawLog.toFile()));
        assertEquals(17, handler.resolveExitCode(17, rawLog.toFile()));
    }
}
