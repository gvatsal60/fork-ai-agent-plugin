package io.jenkins.plugins.aiagentjob;

import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.List;

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
                            + "use OpenCode.");
        }
    }

    /** Whether this handler provides a bidirectional Jenkins approval channel. */
    public boolean supportsManualApprovals() {
        return false;
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

    /** Immutable settings for an Agent Client Protocol execution. */
    public static final class AcpExecutionSpec {
        private final List<String> command;
        private final String model;
        private final String reasoningEffort;

        public AcpExecutionSpec(List<String> command, String model, String reasoningEffort) {
            this.command = command == null ? List.of() : List.copyOf(command);
            this.model = model == null ? "" : model;
            this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
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
    }
}
