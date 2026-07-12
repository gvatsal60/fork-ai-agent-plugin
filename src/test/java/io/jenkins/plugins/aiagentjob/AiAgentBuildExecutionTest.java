package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import hudson.slaves.WorkspaceList;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.codex.CodexAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

@WithJenkins
class AiAgentBuildExecutionTest {

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
    void runsAgentCommandAndCapturesConversation(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-success",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"hello from test\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertFalse(action.getEvents().isEmpty());
        assertTrue(action.getRawLogFile().exists());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_runsBeforeAgent(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-setup-script",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("export SETUP_DONE=yes");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"setup=$SETUP_DONE\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getRawLogFile().exists());
        String rawLog =
                new String(java.nio.file.Files.readAllBytes(action.getRawLogFile().toPath()));
        assertTrue(
                rawLog.contains("setup=yes"),
                "Agent command should see variable exported by setup script");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_failureAbortsBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-setup-fail",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("exit 42");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"should not run\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        String log = jenkins.getLog(build);
        assertFalse(log.contains("should not run"), "Agent should NOT have run");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_emptyIsSkipped(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-no-setup",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"direct run\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertFalse(
                log.contains("Setup script will run before the agent"),
                "Should not mention setup script");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_receivesEnvironmentVariables(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-setup-env",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setEnvironmentVariables("CUSTOM_VAR=secret_value_123");
                            b.setSetupScript("echo GOT_$CUSTOM_VAR");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertTrue(log.contains("GOT_secret_value_123"), "Setup script should see custom env vars");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_exportsFlowToAgentCommand(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-setup-export",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("export MY_SETUP_VAR=from_setup_script");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"val=$MY_SETUP_VAR\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertTrue(
                log.contains("val=from_setup_script"),
                "Exported var from setup should be visible in agent command");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void codexCustomConfig_createsRunScopedCodexHome(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-codex-cfg",
                        b -> {
                            CodexAgentHandler codex = new CodexAgentHandler();
                            codex.setCustomConfigEnabled(true);
                            codex.setCustomConfigToml("[mcp_servers.demo]\ncommand = \"npx\"");
                            b.setAgent(codex);
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "cfg=\"$USERPROFILE/.codex/config.toml\"; "
                                            + "if test -f \"$cfg\"; then echo CODEX_CONFIG_FOUND; sed -n '1,200p' \"$cfg\"; "
                                            + "else echo CODEX_CONFIG_MISSING home=$HOME userprofile=$USERPROFILE; fi; "
                                            + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String log = new String(java.nio.file.Files.readAllBytes(action.getRawLogFile().toPath()));
        assertTrue(
                log.contains("CODEX_CONFIG_FOUND"),
                "Codex config should be found in the run-scoped home");
        assertTrue(
                log.contains("[mcp_servers.demo]"),
                "Codex config should be written to run-scoped home");
        assertTrue(log.contains("command = \"npx\""), "Codex config should preserve TOML content");
        assertFalse(log.contains("CODEX_CONFIG_MISSING"), "Codex config should not be missing");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void failsWhenApprovalTimesOut(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-approval-timeout",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("needs approval");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(1);
                            b.setFailOnAgentError(true);
                            b.setCommandOverride(
                                    "echo '{\"type\":\"tool_call\",\"tool_name\":\"bash\",\"tool_call_id\":\"call-1\",\"text\":\"ls\"}'; "
                                            + "sleep 2; "
                                            + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openCodeAcp_waitsForJenkinsApprovalBeforeToolExecution(JenkinsRule jenkins)
            throws Exception {
        File fakeBin = new File(jenkins.jenkins.getRootDir(), "fake-opencode-bin");
        assertTrue(fakeBin.mkdirs());
        File fakeOpenCode = new File(fakeBin, "opencode");
        try (InputStream fixture =
                getClass()
                        .getResourceAsStream(
                                "/io/jenkins/plugins/aiagentjob/fixtures/fake-opencode-acp.sh")) {
            assertNotNull(fixture);
            Files.copy(fixture, fakeOpenCode.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(fakeOpenCode.setExecutable(true));

        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-opencode-acp-approval",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("create approved.txt");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(30);
                            b.setModel("test/provider-model");
                            b.setExtraArgs("--variant high --format json --pure");
                            b.setSetupScript("cd \"${WORKSPACE}\"");
                            b.setEnvironmentVariables("PATH=" + path);
                            b.setFailOnAgentError(true);
                        });
        DumbSlave agent = jenkins.createOnlineSlave();
        project.setAssignedNode(agent);

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild runningBuild = future.waitForStart();
        ExecutionRegistry.PendingApproval pending =
                waitForPendingApproval(jenkins, runningBuild, future);
        AiAgentRunAction action = runningBuild.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        ExecutionRegistry.LiveExecution liveExecution =
                ExecutionRegistry.get(runningBuild, action.getLatestInvocationId());
        assertNotNull(liveExecution);
        FilePath runningWorkspace = runningBuild.getWorkspace();
        assertNotNull(runningWorkspace);
        assertFalse(runningWorkspace.child("approved.txt").exists());
        assertTrue(liveExecution.approve(pending.getId()));

        FreeStyleBuild build = future.get(20, TimeUnit.SECONDS);
        jenkins.assertBuildStatusSuccess(build);
        FilePath buildWorkspace = build.getWorkspace();
        assertNotNull(buildWorkspace);
        assertTrue(buildWorkspace.child("approved.txt").exists());
        assertTrue(
                buildWorkspace
                        .child("approval-response.json")
                        .readToString()
                        .contains("\"optionId\":\"once\""));
        String configRequests = buildWorkspace.child("config-requests.jsonl").readToString();
        assertTrue(
                configRequests.contains(
                        "\"configId\":\"model\",\"value\":\"test/provider-model\""));
        assertTrue(configRequests.contains("\"configId\":\"effort\",\"value\":\"high\""));
        assertTrue(buildWorkspace.child("acp-command.txt").readToString().contains("acp --pure"));

        String rawLog = Files.readString(action.getRawLogFile().toPath());
        assertTrue(rawLog.contains("session/request_permission"));
        assertFalse(rawLog.contains("\"configOptions\""));
        assertFalse(rawLog.contains("private-test-command"));
        assertFalse(rawLog.contains("private-test-content"));
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
        assertTrue(action.getEvents().stream().anyMatch(e -> "assistant".equals(e.getCategory())));
    }

    private static ExecutionRegistry.PendingApproval waitForPendingApproval(
            JenkinsRule jenkins, FreeStyleBuild build, QueueTaskFuture<FreeStyleBuild> future)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
            int invocationId = action == null ? 0 : action.getLatestInvocationId();
            ExecutionRegistry.LiveExecution liveExecution =
                    invocationId == 0 ? null : ExecutionRegistry.get(build, invocationId);
            if (liveExecution != null && !liveExecution.getPendingApprovals().isEmpty()) {
                return liveExecution.getPendingApprovals().get(0);
            }
            if (future.isDone()) {
                FreeStyleBuild completed = future.get();
                throw new AssertionError(
                        "OpenCode ACP build completed before requesting approval:\n"
                                + jenkins.getLog(completed));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("OpenCode ACP approval request did not reach Jenkins.");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void commandOverride_receivesStepEnvironmentVariables(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-step-env",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"step=$SURROUNDING_VAR\\\"}\"");
                            b.setFailOnAgentError(true);
                        });
        project.addProperty(
                new ParametersDefinitionProperty(
                        new StringParameterDefinition("SURROUNDING_VAR", "default")));

        FreeStyleBuild build =
                project.scheduleBuild2(
                                0,
                                new ParametersAction(
                                        new StringParameterValue(
                                                "SURROUNDING_VAR", "from-parameter")))
                        .get();
        jenkins.assertBuildStatusSuccess(build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());
        assertTrue(
                rawLog.contains("step=from-parameter"),
                "Command override should inherit step-scoped env vars");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_usesAgentLocalTempPathOnRemoteNode(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = jenkins.createOnlineSlave();
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-remote-setup-temp",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("echo SETUP_SCRIPT_PATH=$0");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"remote\"}'");
                            b.setFailOnAgentError(true);
                        });
        project.setAssignedNode(agent);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        FilePath workspace = project.getSomeWorkspace();
        assertNotNull(workspace);
        FilePath tempRoot = WorkspaceList.tempDir(workspace);
        assertNotNull(tempRoot);

        String log = jenkins.getLog(build);
        assertTrue(
                log.contains("SETUP_SCRIPT_PATH=" + tempRoot.getRemote()),
                "Setup script should run from the agent temp area");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void codexCustomConfig_usesAgentLocalTempPathOnRemoteNode(JenkinsRule jenkins)
            throws Exception {
        DumbSlave agent = jenkins.createOnlineSlave();
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-remote-codex-home",
                        b -> {
                            CodexAgentHandler codex = new CodexAgentHandler();
                            codex.setCustomConfigEnabled(true);
                            codex.setCustomConfigToml("model = \"gpt-5.5\"");
                            b.setAgent(codex);
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "cfg=\"$HOME/.codex/config.toml\"; "
                                            + "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"cfg=$cfg home=$HOME\\\"}\"");
                            b.setFailOnAgentError(true);
                        });
        project.setAssignedNode(agent);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        FilePath workspace = project.getSomeWorkspace();
        assertNotNull(workspace);
        FilePath tempRoot = WorkspaceList.tempDir(workspace);
        assertNotNull(tempRoot);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());
        String expectedHomePrefix =
                "home=" + tempRoot.getRemote() + File.separator + "ai-agent-codex-home-";
        assertTrue(
                rawLog.contains(expectedHomePrefix),
                "Codex home should come from the agent temp area");
        assertTrue(
                rawLog.contains("/.codex/config.toml"),
                "Codex config path should resolve inside the run-scoped home");
    }
}
