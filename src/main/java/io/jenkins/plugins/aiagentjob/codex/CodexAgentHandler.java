package io.jenkins.plugins.aiagentjob.codex;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentExecutionCustomization;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTempFiles;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CodexAgentHandler extends AiAgentTypeHandler {
    private static final Set<String> REASONING_EFFORTS =
            Set.of("low", "medium", "high", "xhigh", "max", "ultra");

    private boolean customConfigEnabled;
    private String customConfigToml = "";
    private String additionalGlobalArgs = "";
    private String additionalExecArgs = "";

    @DataBoundConstructor
    public CodexAgentHandler() {}

    @Override
    public String getId() {
        return "CODEX";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "OPENAI_API_KEY";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return REASONING_EFFORTS;
    }

    @Override
    public void validateExecution(AiAgentConfiguration config) {
        if (config.isRequireApprovals() && !config.isYoloMode()) {
            throw new IllegalArgumentException(
                    "Codex CLI does not expose a non-interactive approval channel. "
                            + "Disable manual approvals or use an agent with ACP support.");
        }
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("codex");
        addTokenizedArgs(command, additionalGlobalArgs);
        if (config.isYoloMode()) {
            command.add("--dangerously-bypass-approvals-and-sandbox");
        } else {
            command.add("--sandbox");
            command.add("workspace-write");
            command.add("--ask-for-approval");
            command.add("never");
        }
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        String model = Util.fixEmptyAndTrim(selection.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        String reasoningEffort = Util.fixEmptyAndTrim(selection.getReasoningEffort());
        if (reasoningEffort != null) {
            command.add("-c");
            command.add("model_reasoning_effort=" + tomlString(reasoningEffort));
        }
        command.add("exec");
        command.add("--ephemeral");
        command.add("--json");
        command.add("--skip-git-repo-check");
        addTokenizedArgs(command, additionalExecArgs);
        command.add(prompt);
        return command;
    }

    private static void addTokenizedArgs(List<String> command, String rawArgs) {
        String args = Util.fixEmptyAndTrim(rawArgs);
        if (args == null) {
            return;
        }
        for (String arg : Util.tokenize(args)) {
            command.add(arg);
        }
    }

    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public AiAgentExecutionCustomization prepareExecution(
            AiAgentConfiguration config, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
        if (!customConfigEnabled) {
            return customization;
        }
        FilePath tempDir = AiAgentTempFiles.tempRoot(workspace);
        FilePath homeDir = tempDir.child("ai-agent-codex-home-" + System.nanoTime());
        FilePath codexDir = homeDir.child(".codex");
        FilePath configFile = codexDir.child("config.toml");
        try {
            homeDir.mkdirs();
            homeDir.chmod(0700);
            codexDir.mkdirs();
            codexDir.chmod(0700);
            configFile.write(Util.fixNull(customConfigToml), "UTF-8");
            configFile.chmod(0600);
        } catch (IOException | InterruptedException e) {
            try {
                homeDir.deleteRecursive();
            } catch (IOException | InterruptedException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        String codexHome = homeDir.getRemote();
        customization.putEnvironment("HOME", codexHome);
        customization.putEnvironment("USERPROFILE", codexHome);
        customization.putEnvironment("CODEX_HOME", codexDir.getRemote());
        customization.addCleanupAction(homeDir::deleteRecursive);
        listener.getLogger()
                .println("[ai-agent] Using job-scoped Codex config.toml from agent configuration.");
        return customization;
    }

    public boolean isCustomConfigEnabled() {
        return customConfigEnabled;
    }

    @DataBoundSetter
    public void setCustomConfigEnabled(boolean customConfigEnabled) {
        this.customConfigEnabled = customConfigEnabled;
    }

    public String getCustomConfigToml() {
        return customConfigToml;
    }

    @DataBoundSetter
    public void setCustomConfigToml(String customConfigToml) {
        this.customConfigToml = Util.fixNull(customConfigToml);
    }

    public String getAdditionalGlobalArgs() {
        return additionalGlobalArgs;
    }

    @DataBoundSetter
    public void setAdditionalGlobalArgs(String additionalGlobalArgs) {
        this.additionalGlobalArgs = Util.fixNull(additionalGlobalArgs);
    }

    public String getAdditionalExecArgs() {
        return additionalExecArgs;
    }

    @DataBoundSetter
    public void setAdditionalExecArgs(String additionalExecArgs) {
        this.additionalExecArgs = Util.fixNull(additionalExecArgs);
    }

    private Object readResolve() {
        customConfigToml = Util.fixNull(customConfigToml);
        additionalGlobalArgs = Util.fixNull(additionalGlobalArgs);
        additionalExecArgs = Util.fixNull(additionalExecArgs);
        return this;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return CodexLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return CodexStatsExtractor.INSTANCE;
    }

    @Extension
    @Symbol("codex")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Codex CLI";
        }
    }
}
