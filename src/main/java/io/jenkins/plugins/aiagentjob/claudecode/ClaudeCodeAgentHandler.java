package io.jenkins.plugins.aiagentjob.claudecode;

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

public final class ClaudeCodeAgentHandler extends AiAgentTypeHandler {
    private static final Set<String> REASONING_EFFORTS =
            Set.of("low", "medium", "high", "xhigh", "max");

    @DataBoundConstructor
    public ClaudeCodeAgentHandler() {}

    @Override
    public String getId() {
        return "CLAUDE_CODE";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "ANTHROPIC_API_KEY";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return REASONING_EFFORTS;
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        String executablePath = Util.fixEmptyAndTrim(config.getExecutablePath());
        if (executablePath == null || isNpx(executablePath)) {
            command.add("npx");
            command.add("-y");
            command.add("@anthropic-ai/claude-code");
        } else {
            command.add("claude");
        }
        command.add("-p");
        command.add(prompt);
        command.add("--output-format=stream-json");
        command.add("--verbose");
        command.add("--no-session-persistence");
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

    private static boolean isNpx(String executablePath) {
        String normalized = executablePath.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return "npx".equalsIgnoreCase(name) || "npx.cmd".equalsIgnoreCase(name);
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return ClaudeCodeLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return ClaudeCodeStatsExtractor.INSTANCE;
    }

    @Extension
    @Symbol("claudeCode")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Claude Code";
        }
    }
}
