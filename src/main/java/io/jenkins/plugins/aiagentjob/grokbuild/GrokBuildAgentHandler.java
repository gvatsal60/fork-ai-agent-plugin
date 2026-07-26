package io.jenkins.plugins.aiagentjob.grokbuild;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GrokBuildAgentHandler extends AiAgentTypeHandler {
    private static final Set<String> ACP_AGENT_FLAGS =
            Set.of("--reauth", "----reauthenticate", "--leader", "--no-leader", "--debug");
    private static final Set<String> ACP_AGENT_OPTIONS =
            Set.of(
                    "--agent-profile",
                    "--plugin-dir",
                    "--grok-ws-origin",
                    "--grok-ws-url",
                    "--cli-chat-proxy-base-url",
                    "--xai-api-base-url",
                    "--debug-file",
                    "--leader-socket");
    private static final Set<String> HEADLESS_ONLY_OPTIONS =
            Set.of("-p", "--single", "--prompt-json", "--prompt-file", "--output-format");
    private static final Set<String> APPROVAL_BYPASS_OPTIONS =
            Set.of("--allow", "--allowedTools", "--allowed-tools");
    private static final Set<String> APPROVAL_BYPASS_FLAGS =
            Set.of("--always-approve", "--yolo", "--dangerously-skip-permissions");

    @DataBoundConstructor
    public GrokBuildAgentHandler() {}

    @Override
    public String getId() {
        return "GROK_BUILD";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "XAI_API_KEY";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return Set.of("low", "medium", "high");
    }

    @Override
    public boolean supportsManualApprovals() {
        return true;
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("grok");
        command.add("--no-auto-update");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("streaming-json");
        if (config.isYoloMode()) {
            command.add("--always-approve");
        } else {
            command.add("--permission-mode");
            command.add("auto");
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
            command.add("--reasoning-effort");
            command.add(reasoningEffort);
        }
        return command;
    }

    @Override
    public AcpExecutionSpec buildAcpExecution(AiAgentConfiguration config) {
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        String model = selection.getModel();
        String reasoningEffort = selection.getReasoningEffort();
        List<String> globalArgs = new ArrayList<>();
        List<String> agentArgs = new ArrayList<>();
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
            if ("--reasoning-effort".equals(arg) || "--effort".equals(arg)) {
                if (i + 1 < extraArgs.size()) {
                    reasoningEffort = extraArgs.get(++i);
                }
                continue;
            }
            if (arg.startsWith("--reasoning-effort=") || arg.startsWith("--effort=")) {
                reasoningEffort = arg.substring(arg.indexOf('=') + 1);
                continue;
            }
            if ("--permission-mode".equals(arg)
                    || HEADLESS_ONLY_OPTIONS.contains(arg)
                    || APPROVAL_BYPASS_OPTIONS.contains(arg)) {
                if (i + 1 < extraArgs.size()) {
                    i++;
                }
                continue;
            }
            if (arg.startsWith("--permission-mode=")
                    || startsWithHeadlessOnlyOption(arg)
                    || startsWithApprovalBypassOption(arg)
                    || APPROVAL_BYPASS_FLAGS.contains(arg)
                    || startsWithApprovalBypassFlag(arg)
                    || "--no-auto-update".equals(arg)) {
                continue;
            }
            if (ACP_AGENT_FLAGS.contains(arg)) {
                agentArgs.add(arg);
                continue;
            }
            if (ACP_AGENT_OPTIONS.contains(arg)) {
                agentArgs.add(arg);
                if (i + 1 < extraArgs.size()) {
                    agentArgs.add(extraArgs.get(++i));
                }
                continue;
            }
            if (startsWithAgentOption(arg)) {
                agentArgs.add(arg);
                continue;
            }
            globalArgs.add(arg);
        }

        List<String> command = new ArrayList<>();
        command.add("grok");
        command.add("--no-auto-update");
        command.add("--permission-mode");
        command.add("default");
        command.addAll(globalArgs);
        command.add("agent");
        if (!model.isEmpty()) {
            command.add("--model");
            command.add(model);
        }
        if (!reasoningEffort.isEmpty()) {
            command.add("--reasoning-effort");
            command.add(reasoningEffort);
        }
        command.addAll(agentArgs);
        command.add("stdio");

        return new AcpExecutionSpec(
                command,
                "",
                "",
                Map.of(config.getEffectiveApiKeyEnvVar(), "xai.api_key"),
                List.of("cached_token"));
    }

    private static boolean startsWithHeadlessOnlyOption(String arg) {
        for (String option : HEADLESS_ONLY_OPTIONS) {
            if (arg.startsWith(option + "=")) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAgentOption(String arg) {
        for (String option : ACP_AGENT_OPTIONS) {
            if (arg.startsWith(option + "=")) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithApprovalBypassOption(String arg) {
        for (String option : APPROVAL_BYPASS_OPTIONS) {
            if (arg.startsWith(option + "=")) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithApprovalBypassFlag(String arg) {
        for (String flag : APPROVAL_BYPASS_FLAGS) {
            if (arg.startsWith(flag + "=")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return GrokBuildLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return GrokBuildStatsExtractor.INSTANCE;
    }

    @Extension
    @Symbol("grok")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Grok Build";
        }
    }
}
