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

public final class ClaudeCodeAgentHandler extends AiAgentTypeHandler {
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
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("npx");
        command.add("-y");
        command.add("@anthropic-ai/claude-code");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format=stream-json");
        command.add("--verbose");
        if (config.isYoloMode()) {
            command.add("--dangerously-skip-permissions");
        }
        String model = Util.fixEmptyAndTrim(config.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        String reasoningEffort = Util.fixEmptyAndTrim(config.getReasoningEffort());
        if (reasoningEffort != null) {
            command.add("--effort");
            command.add(reasoningEffort);
        }
        return command;
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
