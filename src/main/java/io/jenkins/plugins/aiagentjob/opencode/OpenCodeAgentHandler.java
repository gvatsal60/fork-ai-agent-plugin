package io.jenkins.plugins.aiagentjob.opencode;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentExecutionCustomization;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class OpenCodeAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public OpenCodeAgentHandler() {}

    @Override
    public String getId() {
        return "OPENCODE";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "OPENAI_API_KEY";
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("opencode");
        command.add("run");
        command.add("--format");
        command.add("json");
        String model = Util.fixEmptyAndTrim(config.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        String reasoningEffort = Util.fixEmptyAndTrim(config.getReasoningEffort());
        if (reasoningEffort != null) {
            command.add("--variant");
            command.add(reasoningEffort);
        }
        command.add(prompt);
        return command;
    }

    @Override
    public AcpExecutionSpec buildAcpExecution(AiAgentConfiguration config) {
        List<String> command = new ArrayList<>();
        command.add("opencode");
        command.add("acp");

        String model = Util.fixNull(config.getModel()).trim();
        String reasoningEffort = Util.fixNull(config.getReasoningEffort()).trim();
        List<String> extraArgs =
                new ArrayList<>(Arrays.asList(Util.tokenize(Util.fixNull(config.getExtraArgs()))));
        for (int i = 0; i < extraArgs.size(); i++) {
            String arg = extraArgs.get(i);
            if ("--model".equals(arg) || "-m".equals(arg)) {
                if (i + 1 < extraArgs.size()) {
                    model = extraArgs.get(++i);
                }
                continue;
            }
            if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
                continue;
            }
            if ("--variant".equals(arg)) {
                if (i + 1 < extraArgs.size()) {
                    reasoningEffort = extraArgs.get(++i);
                }
                continue;
            }
            if (arg.startsWith("--variant=")) {
                reasoningEffort = arg.substring("--variant=".length());
                continue;
            }
            if ("--format".equals(arg)) {
                if (i + 1 < extraArgs.size()) {
                    i++;
                }
                continue;
            }
            if (arg.startsWith("--format=")) {
                continue;
            }
            command.add(arg);
        }

        return new AcpExecutionSpec(command, model, reasoningEffort);
    }

    @Override
    public AiAgentExecutionCustomization prepareExecution(
            AiAgentConfiguration config, FilePath workspace, TaskListener listener) {
        AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
        if (config.isYoloMode()) {
            customization.putEnvironment(
                    "OPENCODE_PERMISSION",
                    "{\"edit\":\"allow\",\"bash\":\"allow\",\"webfetch\":\"allow\",\"external_directory\":\"allow\",\"doom_loop\":\"allow\"}");
        } else if (config.isRequireApprovals()) {
            customization.putEnvironment(
                    "OPENCODE_PERMISSION",
                    "{\"edit\":\"ask\",\"bash\":\"ask\",\"webfetch\":\"ask\",\"external_directory\":\"ask\",\"doom_loop\":\"ask\"}");
        }
        return customization;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return OpenCodeLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return OpenCodeStatsExtractor.INSTANCE;
    }

    @Extension
    @Symbol("openCode")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "OpenCode";
        }
    }
}
