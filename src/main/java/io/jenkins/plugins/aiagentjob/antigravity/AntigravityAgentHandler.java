package io.jenkins.plugins.aiagentjob.antigravity;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONObject;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
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

    @Override
    public int resolveExitCode(int processExitCode, File rawLogFile) throws IOException {
        if (processExitCode != 0 || rawLogFile == null || !rawLogFile.isFile()) {
            return processExitCode;
        }
        return hasSemanticFailure(rawLogFile) ? 1 : 0;
    }

    private static boolean hasSemanticFailure(File rawLogFile) throws IOException {
        Set<String> failedTools = new HashSet<>();
        boolean sawResult = false;
        boolean emptySuccess = false;
        try (BufferedReader reader =
                Files.newBufferedReader(rawLogFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject json;
                try {
                    json = JSONObject.fromObject(line);
                } catch (RuntimeException ignored) {
                    continue;
                }
                String event = LogFormatUtils.normalize(json.optString("event", ""));
                if ("step_update".equals(event)) {
                    updateFailedTools(json.optJSONObject("step_update"), failedTools);
                } else if ("result".equals(event)) {
                    JSONObject result = json.optJSONObject("result");
                    if (result == null) {
                        continue;
                    }
                    sawResult = true;
                    String status = LogFormatUtils.normalize(result.optString("status", ""));
                    if (AntigravityLogFormat.isFailureState(status)
                            || !AntigravityLogFormat.errorMessage(result).isEmpty()) {
                        return true;
                    }
                    if ("success".equals(status)) {
                        emptySuccess = LogFormatUtils.firstNonEmpty(result, "response").isEmpty();
                        if (!emptySuccess) {
                            return false;
                        }
                    }
                }
            }
        }
        return !failedTools.isEmpty() && (!sawResult || emptySuccess);
    }

    private static void updateFailedTools(JSONObject update, Set<String> failedTools) {
        if (update == null
                || !"tool".equals(LogFormatUtils.normalize(update.optString("step_type", "")))) {
            return;
        }
        String stepId = AntigravityLogFormat.stepId(update);
        if (stepId.isEmpty()) {
            stepId = "unknown";
        }
        String state = LogFormatUtils.normalize(update.optString("state", ""));
        if (AntigravityLogFormat.isFailureState(state)) {
            failedTools.add(stepId);
        } else if ("done".equals(state)) {
            failedTools.remove(stepId);
        }
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
