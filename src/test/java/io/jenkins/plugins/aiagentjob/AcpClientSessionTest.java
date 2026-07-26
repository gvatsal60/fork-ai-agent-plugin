package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Proc;

import io.jenkins.plugins.aiagentjob.grokbuild.GrokBuildAgentHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@WithJenkins
class AcpClientSessionTest {

    @TempDir Path tempDirectory;

    @Test
    void usesApiKeyWhenProcessBootstrapReportsIt(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","method":"ai-agent/auth_environment","params":{"name":"XAI_API_KEY"}}
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of("XAI_API_KEY", "xai.api_key"),
                            List.of("cached_token"),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesCachedIdentityWhenProcessBootstrapDoesNotProvideApiKey(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of("XAI_API_KEY", "xai.api_key"),
                            List.of("cached_token"),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"cached_token\""));
            assertFalse(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesAdvertisedApiKeyWhenConfiguredOutsideProcessEnvironment(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);
        AiAgentBuilder config = new AiAgentBuilder();
        config.setAgent(new GrokBuildAgentHandler());
        AiAgentTypeHandler.AcpExecutionSpec execution = config.getAgent().buildAcpExecution(config);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            execution.getAuthenticationMethods(),
                            execution.getFallbackAuthenticationMethods(),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void timesOutWhenAuthenticationDoesNotRespond(JenkinsRule jenkins) throws Exception {
        String initialize =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"}]}}
                """;
        FakeProc proc = new FakeProc(initialize, true);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(200));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of("XAI_API_KEY", "xai.api_key"),
                                            List.of(),
                                            Map.of("XAI_API_KEY", "fixture-key")));

            assertTrue(error.getMessage().contains("ACP authenticate timed out"));
            assertFalse(proc.isAlive());
        } finally {
            proc.kill();
        }
    }

    @Test
    void doesNotFallBackToCachedIdentityAfterApiKeyFailure(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"invalid API key"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of("XAI_API_KEY", "xai.api_key"),
                                            List.of("cached_token"),
                                            Map.of("XAI_API_KEY", "invalid-fixture-key")));

            assertTrue(error.getMessage().contains("invalid API key"));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
            assertFalse(proc.stdinText().contains("\"methodId\":\"cached_token\""));
        } finally {
            proc.kill();
        }
    }

    private AiAgentExecutor.AgentOutputHandler newOutputHandler() throws IOException {
        File rawLog = tempDirectory.resolve("raw-" + System.nanoTime() + ".jsonl").toFile();
        return new AiAgentExecutor.AgentOutputHandler(
                new ByteArrayOutputStream(),
                rawLog,
                new ExecutionRegistry.LiveExecution(),
                List.of());
    }

    private static final class FakeProc extends Proc {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile boolean alive = true;

        FakeProc(String responses, boolean keepOutputOpen) throws IOException {
            serverOutput = new PipedOutputStream(stdout);
            serverOutput.write(responses.getBytes(StandardCharsets.UTF_8));
            serverOutput.flush();
            if (!keepOutputOpen) {
                serverOutput.close();
            }
        }

        String stdinText() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void kill() throws IOException {
            alive = false;
            serverOutput.close();
            stdout.close();
        }

        @Override
        public int join() {
            alive = false;
            return 0;
        }

        @Override
        public InputStream getStdout() {
            return stdout;
        }

        @Override
        public InputStream getStderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getStdin() {
            return stdin;
        }
    }
}
