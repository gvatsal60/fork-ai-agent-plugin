package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;

import io.jenkins.plugins.aiagentjob.antigravity.AntigravityAgentHandler;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;

import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@WithJenkins
class AiAgentCredentialInjectionTest {

    private FreeStyleProject newProject(
            JenkinsRule jenkins, String name, java.util.function.Consumer<AiAgentBuilder> cfg)
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        AiAgentBuilder builder = new AiAgentBuilder();
        cfg.accept(builder);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void injectsApiKeyFromCredential(JenkinsRule jenkins) throws Exception {
        // Store a secret text credential
        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "test-api-key",
                        "Test API key",
                        Secret.fromString("sk-test-secret-12345"));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        // Create project that echoes back the env var to prove injection
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "credential-inject-test",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("test");
                            b.setApiCredentialsId("test-api-key");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"system\\\",\\\"key_set\\\":\\\"$(test -n \\\"$ANTHROPIC_API_KEY\\\" && echo yes || echo no)\\\",\\\"key\\\":\\\"$ANTHROPIC_API_KEY\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        String rawLog = Files.readString(action.getRawLogFile().toPath(), StandardCharsets.UTF_8);
        assertTrue(rawLog.contains("\"key_set\":\"yes\""), "Env var should have been set");
        assertTrue(rawLog.contains("\"key\":\"****\""), "Raw log should mask the API key");
        assertFalse(rawLog.contains("sk-test-secret-12345"));

        String buildLog = build.getLog();
        assertTrue(
                buildLog.contains("API key injected as ANTHROPIC_API_KEY"),
                "Build log should mention API key injection");
        assertFalse(buildLog.contains("sk-test-secret-12345"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void usesCustomEnvVarOverride(JenkinsRule jenkins) throws Exception {
        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "custom-key",
                        "Custom key",
                        Secret.fromString("my-custom-secret"));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        FreeStyleProject project =
                newProject(
                        jenkins,
                        "custom-envvar-test",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("test");
                            b.setApiCredentialsId("custom-key");
                            b.setApiEnvVarName("ANTHROPIC_API_KEY");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"system\\\",\\\"key_set\\\":\\\"$(test -n \\\"$ANTHROPIC_API_KEY\\\" && echo yes || echo no)\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertTrue(
                buildLog.contains("API key injected as ANTHROPIC_API_KEY"),
                "Should inject as ANTHROPIC_API_KEY not OPENAI_API_KEY");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void masksMultilineAndShellMangledCredentialValues(JenkinsRule jenkins) throws Exception {
        String firstLine = "multiline-secret-first";
        String secondLine = "second-line's-secret";
        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "multiline-key",
                        "Multiline key",
                        Secret.fromString(firstLine + "\n" + secondLine));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        FreeStyleProject project =
                newProject(
                        jenkins,
                        "multiline-credential-test",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setApiCredentialsId("multiline-key");
                            b.setSetupScript("true");
                            b.setCommandOverride("printf '%s\\n' \"$ANTHROPIC_API_KEY\"");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath(), StandardCharsets.UTF_8);
        String buildLog = build.getLog();

        for (String secretFragment :
                new String[] {firstLine, secondLine, "second-line'\\''s-secret"}) {
            assertFalse(rawLog.contains(secretFragment), "Raw log leaked credential fragment");
            assertFalse(buildLog.contains(secretFragment), "Build log leaked credential fragment");
        }
        assertTrue(rawLog.contains("****"));
        assertTrue(buildLog.contains("****"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void masksJsonEscapedCredentialValues(JenkinsRule jenkins) throws Exception {
        String secret = "double\"quote\\path";
        String jsonEscapedSecret = "double\\\"quote\\\\path";
        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "json-escaped-key",
                        "JSON escaped key",
                        Secret.fromString(secret));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        FreeStyleProject project =
                newProject(
                        jenkins,
                        "json-escaped-credential-test",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setApiCredentialsId("json-escaped-key");
                            b.setCommandOverride(
                                    "printf '%s\\n' '{\"type\":\"system\",\"message\":\"double\\\"quote\\\\path\"}'");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath(), StandardCharsets.UTF_8);
        String buildLog = build.getLog();

        for (String secretVariant : new String[] {secret, jsonEscapedSecret}) {
            assertFalse(rawLog.contains(secretVariant), "Raw log leaked credential variant");
            assertFalse(buildLog.contains(secretVariant), "Build log leaked credential variant");
        }
        assertTrue(rawLog.contains("\"message\":\"****\""));
        assertTrue(buildLog.contains("\"message\":\"****\""));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void warnsWhenCredentialNotFound(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "missing-cred-test",
                        b -> {
                            b.setAgent(new GeminiCliAgentHandler());
                            b.setPrompt("test");
                            b.setApiCredentialsId("nonexistent-credential-id");
                            b.setCommandOverride("echo '{\"type\":\"system\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertTrue(
                buildLog.contains("WARNING") && buildLog.contains("not found"),
                "Build log should warn about missing credential");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void noCredentialNoInjection(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "no-cred-test",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("test");
                            b.setCommandOverride("echo '{\"type\":\"system\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertFalse(buildLog.contains("API key injected"), "Should not mention API key injection");
    }

    @Test
    void effectiveApiKeyEnvVar_defaultsToAgentType() {
        AiAgentBuilder project = new AiAgentBuilder();
        project.setAgent(new ClaudeCodeAgentHandler());
        project.setApiEnvVarName("");
        assertEquals("ANTHROPIC_API_KEY", project.getEffectiveApiKeyEnvVar());

        project.setAgent(new GeminiCliAgentHandler());
        assertEquals("GEMINI_API_KEY", project.getEffectiveApiKeyEnvVar());

        project.setAgent(new AntigravityAgentHandler());
        assertEquals("", project.getEffectiveApiKeyEnvVar());
    }

    @Test
    void effectiveApiKeyEnvVar_respectsCustomOverride() {
        AiAgentBuilder project = new AiAgentBuilder();
        project.setAgent(new OpenCodeAgentHandler());
        project.setApiEnvVarName("ANTHROPIC_API_KEY");
        assertEquals("ANTHROPIC_API_KEY", project.getEffectiveApiKeyEnvVar());
    }
}
