package io.jenkins.plugins.aiagentjob.cursor;

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

public final class CursorAgentHandler extends AiAgentTypeHandler {
    private static final Set<String> REASONING_EFFORTS =
            Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

    @DataBoundConstructor
    public CursorAgentHandler() {}

    @Override
    public String getId() {
        return "CURSOR_AGENT";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "CURSOR_API_KEY";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return REASONING_EFFORTS;
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("agent");
        command.add("-p");
        command.add("--output-format=stream-json");
        command.add("--trust");
        if (config.isYoloMode()) {
            command.add("--yolo");
            command.add("--approve-mcps");
        }
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        String model = Util.fixEmptyAndTrim(selection.getModel());
        String reasoningEffort = Util.fixEmptyAndTrim(selection.getReasoningEffort());
        if (model == null && reasoningEffort != null) {
            throw new IllegalArgumentException(
                    "Cursor Agent reasoning effort requires a model because effort is encoded in "
                            + "the model alias.");
        }
        if (model != null) {
            if (reasoningEffort != null) {
                model = withReasoningEffort(model, reasoningEffort);
            }
            command.add("--model");
            command.add(model);
        }
        command.add(prompt);
        return command;
    }

    private static String withReasoningEffort(String model, String reasoningEffort) {
        String fastSuffix = model.endsWith("-fast") ? "-fast" : "";
        String baseModel = fastSuffix.isEmpty() ? model : model.substring(0, model.length() - 5);
        if (baseModel.endsWith("-extra-high")) {
            baseModel = baseModel.substring(0, baseModel.length() - 11);
        } else {
            for (String effort : REASONING_EFFORTS) {
                String suffix = "-" + effort;
                if (baseModel.endsWith(suffix)) {
                    baseModel = baseModel.substring(0, baseModel.length() - suffix.length());
                    break;
                }
            }
        }
        return baseModel + "-" + reasoningEffort + fastSuffix;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return CursorLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return CursorStatsExtractor.INSTANCE;
    }

    @Extension
    @Symbol("cursor")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Cursor Agent";
        }
    }
}
