package io.jenkins.plugins.aiagentjob;

import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.TaskListener;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describable extension point for an AI agent implementation.
 *
 * <p>Implementations must define:
 *
 * <ul>
 *   <li>{@link #getId()} — stable identifier
 *   <li>{@link #getDefaultApiKeyEnvVar()} — env var for the API key
 *   <li>{@link #buildDefaultCommand} — CLI command to launch the agent
 *   <li>{@link #getLogFormat()} — parser for the agent's JSONL output
 *   <li>{@link #getStatsExtractor()} — usage stats extractor for the agent's JSONL output
 * </ul>
 *
 * <p>Implementations may optionally override {@link #prepareExecution} to contribute agent-specific
 * environment setup/cleanup.
 */
public abstract class AiAgentTypeHandler extends AbstractDescribableImpl<AiAgentTypeHandler>
        implements ExtensionPoint {
    /** Stable identifier for this agent implementation. */
    public abstract String getId();

    /** Default API-key environment variable, or empty when the agent uses node-level auth. */
    public abstract String getDefaultApiKeyEnvVar();

    /** Rejects unsupported execution settings before any process or temporary file is created. */
    public void validateExecution(AiAgentConfiguration config) {
        if (!config.isRequireApprovals() || config.isYoloMode()) {
            return;
        }
        String commandOverride = config.getCommandOverride();
        if (commandOverride != null && !commandOverride.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Manual approvals cannot be used with a command override because Jenkins "
                            + "has no bidirectional approval channel for that process.");
        }
        if (!supportsManualApprovals()) {
            throw new IllegalArgumentException(
                    "Manual approvals require an ACP-capable agent. Disable manual approvals or "
                            + "use OpenCode or Grok Build.");
        }
    }

    /** Whether this handler provides a bidirectional Jenkins approval channel. */
    public boolean supportsManualApprovals() {
        return false;
    }

    /** Reasoning efforts recognized when appended to a model identifier. */
    protected Set<String> getSupportedReasoningEfforts() {
        return Set.of();
    }

    /** Resolves the model and optional {@code model:effort} shorthand for this handler. */
    public final ModelSelection resolveModelSelection(
            String configuredModel, String configuredReasoningEffort) {
        String model = Util.fixNull(configuredModel).trim();
        String reasoningEffort = Util.fixNull(configuredReasoningEffort).trim();
        Set<String> supportedEfforts = getSupportedReasoningEfforts();
        if (!supportedEfforts.isEmpty()) {
            int separator = model.lastIndexOf(':');
            if (separator > 0 && separator < model.length() - 1) {
                String suffix = model.substring(separator + 1);
                if (supportedEfforts.contains(suffix)) {
                    if (model.charAt(separator - 1) == ':') {
                        model = model.substring(0, separator - 1) + model.substring(separator);
                    } else {
                        model = model.substring(0, separator);
                        if (reasoningEffort.isEmpty()) {
                            reasoningEffort = suffix;
                        }
                    }
                }
            }
        }
        return new ModelSelection(model, reasoningEffort);
    }

    public abstract List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt);

    /**
     * Returns an ACP server command for a bidirectional approval channel, or {@code null} when
     * manual approvals are unsupported.
     */
    public AcpExecutionSpec buildAcpExecution(AiAgentConfiguration config) {
        return null;
    }

    public AiAgentExecutionCustomization prepareExecution(
            AiAgentConfiguration config, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        return AiAgentExecutionCustomization.empty();
    }

    /**
     * Maps process exit status after complete output has been persisted. Agents that report
     * terminal failures while exiting zero can override this method.
     */
    public int resolveExitCode(int processExitCode, File rawLogFile) throws IOException {
        return processExitCode;
    }

    /**
     * Returns the log format parser for this agent's JSONL output.
     *
     * <p>The returned format's {@link AiAgentLogFormat#classify} method should return {@code null}
     * for any JSON it does not recognise, so the parser can fall through to the shared format and
     * generic fallback.
     */
    public abstract AiAgentLogFormat getLogFormat();

    /**
     * Returns the stats extractor for this agent's JSONL output.
     *
     * <p>The returned extractor's {@link AiAgentStatsExtractor#extract} method should return {@code
     * false} for any JSON it does not recognise, so the shared extractor handles it as a fallback.
     */
    public abstract AiAgentStatsExtractor getStatsExtractor();

    /** Immutable runtime model and reasoning-effort selection. */
    public static final class ModelSelection {
        private final String model;
        private final String reasoningEffort;

        private ModelSelection(String model, String reasoningEffort) {
            this.model = model;
            this.reasoningEffort = reasoningEffort;
        }

        public String getModel() {
            return model;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }
    }

    /** Immutable settings for an Agent Client Protocol execution. */
    public static final class AcpExecutionSpec {
        private final List<String> command;
        private final String model;
        private final String reasoningEffort;
        private final Map<String, String> authenticationMethods;
        private final List<String> fallbackAuthenticationMethods;

        public AcpExecutionSpec(List<String> command, String model, String reasoningEffort) {
            this(command, model, reasoningEffort, Map.of(), List.of());
        }

        public AcpExecutionSpec(
                List<String> command,
                String model,
                String reasoningEffort,
                Map<String, String> authenticationMethods,
                List<String> fallbackAuthenticationMethods) {
            this.command = command == null ? List.of() : List.copyOf(command);
            this.model = model == null ? "" : model;
            this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
            this.authenticationMethods =
                    authenticationMethods == null ? Map.of() : Map.copyOf(authenticationMethods);
            this.fallbackAuthenticationMethods =
                    fallbackAuthenticationMethods == null
                            ? List.of()
                            : List.copyOf(fallbackAuthenticationMethods);
        }

        public List<String> getCommand() {
            return command;
        }

        public String getModel() {
            return model;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        /** Maps required environment variable names to ACP authentication method IDs. */
        public Map<String, String> getAuthenticationMethods() {
            return authenticationMethods;
        }

        /** Authentication methods to try when no method can be selected from the launch env. */
        public List<String> getFallbackAuthenticationMethods() {
            return fallbackAuthenticationMethods;
        }
    }
}
