package io.jenkins.plugins.aiagentjob.antigravity;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AntigravityAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public AntigravityAgentHandler() {}

    @Override
    public String getId() {
        return "ANTIGRAVITY_CLI";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return Set.of("low", "medium", "high");
    }

    @Override
    public void validateExecution(AiAgentConfiguration config) {
        super.validateExecution(config);
        if (Util.fixEmptyAndTrim(config.getApiCredentialsId()) != null
                && Util.fixEmptyAndTrim(config.getEffectiveApiKeyEnvVar()) == null) {
            throw new IllegalArgumentException(
                    "Antigravity CLI uses node-level Google authentication. Set API Key env var "
                            + "override only when using a custom credential-based auth flow.");
        }
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("agy");
        command.add("--print");
        command.add(prompt);
        command.add("--output-format");
        command.add("stream-json");
        if (config.isYoloMode()) {
            command.add("--dangerously-skip-permissions");
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
            command.add("--effort");
            command.add(reasoningEffort);
        }
        return command;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return AntigravityLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return AntigravityStatsExtractor.INSTANCE;
    }

    @Extension
    @Symbol("antigravity")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Antigravity CLI";
        }
    }
}
