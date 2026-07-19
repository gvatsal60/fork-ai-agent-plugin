package io.jenkins.plugins.aiagentjob;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.Util;
import hudson.console.LineTransformationOutputStream;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.StreamCopyThread;

import org.jenkinsci.plugins.credentialsbinding.masking.SecretPatterns;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Runs the AI agent subprocess, wires stdout/stderr to the Jenkins build log and the raw JSONL log
 * file, and handles the approval-gate flow when approvals are enabled.
 */
final class AiAgentExecutor {
    private AiAgentExecutor() {}

    static int execute(
            Run<?, ?> run,
            FilePath workspace,
            EnvVars stepEnv,
            Launcher launcher,
            TaskListener listener,
            AiAgentConfiguration config,
            AiAgentRunAction action)
            throws IOException, InterruptedException {
        EnvVars env = new EnvVars(stepEnv);

        String prompt = Util.replaceMacro(Util.fixNull(config.getPrompt()), env);
        String model = Util.replaceMacro(Util.fixNull(config.getModel()), env);
        String workDirValue = Util.replaceMacro(Util.fixNull(config.getWorkingDirectory()), env);
        String commandOverride = Util.fixNull(config.getCommandOverride()).trim();
        AiAgentTypeHandler agent = config.getAgent();
        agent.validateExecution(config);

        FilePath runDirectory = resolveRunDirectory(workspace, workDirValue);
        runDirectory.mkdirs();

        EnvVars procEnv = new EnvVars(env);
        procEnv.putAll(
                new LinkedHashMap<>(
                        AiAgentCommandFactory.parseEnvironmentVariables(
                                config.getEnvironmentVariables())));
        List<String> sensitiveValues = new ArrayList<>();

        // Inject API key from Jenkins Credentials if configured
        String credentialsId = Util.fixEmptyAndTrim(config.getApiCredentialsId());
        if (credentialsId != null) {
            StringCredentials cred =
                    CredentialsProvider.findCredentialById(
                            credentialsId,
                            StringCredentials.class,
                            run,
                            Collections.<DomainRequirement>emptyList());
            if (cred != null) {
                String envVarName = config.getEffectiveApiKeyEnvVar();
                String secretValue = cred.getSecret().getPlainText();
                procEnv.put(envVarName, secretValue);
                if (!secretValue.isEmpty()) {
                    sensitiveValues.add(secretValue);
                }
                listener.getLogger()
                        .println(
                                "[ai-agent] API key injected as "
                                        + envVarName
                                        + " from credential '"
                                        + credentialsId
                                        + "'");
            } else {
                listener.getLogger()
                        .println(
                                "[ai-agent] WARNING: Credential '"
                                        + credentialsId
                                        + "' not found. Agent may fail to authenticate.");
            }
        }

        procEnv.put("AI_AGENT_PROMPT", prompt);
        procEnv.put("AI_AGENT_MODEL", model);
        procEnv.put("AI_AGENT_REASONING_EFFORT", Util.fixNull(config.getReasoningEffort()));

        String setupScript = Util.fixNull(config.getSetupScript()).trim();
        if (!setupScript.isEmpty() && !launcher.isUnix()) {
            throw new IOException(
                    "Setup script is currently supported only on Unix agents. "
                            + "Use Command override for Windows nodes.");
        }
        AiAgentExecutionCustomization executionCustomization =
                agent.prepareExecution(config, workspace, listener);
        FilePath tempSetupScript = null;
        try {
            procEnv.putAll(executionCustomization.getEnvironment());

            AiAgentTypeHandler.AcpExecutionSpec acpExecution =
                    commandOverride.isEmpty() && config.isRequireApprovals() && !config.isYoloMode()
                            ? agent.buildAcpExecution(config)
                            : null;

            List<String> agentCommand;
            if (!commandOverride.isEmpty()) {
                agentCommand = List.of(commandOverride);
            } else if (acpExecution != null) {
                agentCommand = acpExecution.getCommand();
            } else {
                agentCommand = AiAgentCommandFactory.buildDefaultCommand(config, prompt);
            }

            boolean needsShellEnvironmentBootstrap =
                    launcher.isUnix() && !executionCustomization.getEnvironment().isEmpty();
            boolean disableInteractive = config.isDisableInteractive() && acpExecution == null;
            List<String> command;
            if ((!setupScript.isEmpty() && launcher.isUnix()) || needsShellEnvironmentBootstrap) {
                String combinedScript =
                        buildCombinedScript(
                                setupScript,
                                executionCustomization.getEnvironment(),
                                agentCommand,
                                commandOverride);
                tempSetupScript = writeTempScript(workspace, combinedScript);
                command = buildShellCommand(combinedScript, tempSetupScript);
            } else if (!commandOverride.isEmpty()) {
                if (launcher.isUnix()) {
                    // Use a non-login shell so injected HOME/USERPROFILE are not overridden.
                    command = List.of("/bin/sh", "-c", commandOverride);
                } else {
                    command = List.of("cmd", "/c", commandOverride);
                }
            } else if (!launcher.isUnix()) {
                command = buildWindowsCommand(agentCommand);
            } else {
                command = agentCommand;
            }

            if (!setupScript.isEmpty()) {
                listener.getLogger().println("[ai-agent] Setup script will run before the agent.");
            }

            int invocationId =
                    action.markStarted(
                            agent.getDescriptor().getDisplayName(),
                            "",
                            model,
                            "",
                            config.isYoloMode(),
                            config.isRequireApprovals());

            AgentOutputHandler outputHandler = null;
            boolean registered = false;
            int exitCode;
            try {
                File rawLogFile = action.getRawLogFile(invocationId);
                Files.deleteIfExists(rawLogFile.toPath());

                ExecutionRegistry.LiveExecution liveExecution =
                        ExecutionRegistry.register(run, invocationId);
                registered = true;
                Duration approvalTimeout =
                        Duration.ofSeconds(Math.max(1, config.getApprovalTimeoutSeconds()));

                outputHandler =
                        new AgentOutputHandler(
                                listener.getLogger(),
                                rawLogFile,
                                liveExecution,
                                config.isRequireApprovals()
                                        && !config.isYoloMode()
                                        && acpExecution == null,
                                approvalTimeout,
                                agent.getLogFormat(),
                                sensitiveValues);
                OutputStream stdoutSink = new NonClosingSynchronizedOutputStream(outputHandler);
                OutputStream stderrSink = new NonClosingSynchronizedOutputStream(outputHandler);

                if (acpExecution != null) {
                    exitCode =
                            executeAcpProcess(
                                    launcher,
                                    command,
                                    runDirectory,
                                    procEnv,
                                    listener,
                                    outputHandler,
                                    liveExecution,
                                    approvalTimeout,
                                    prompt,
                                    acpExecution);
                } else {
                    Launcher.ProcStarter procStarter =
                            launcher.launch()
                                    .cmds(command)
                                    .pwd(runDirectory)
                                    .envs(procEnv)
                                    .stdout(stdoutSink)
                                    .stderr(stderrSink)
                                    .quiet(true);
                    if (disableInteractive) {
                        procStarter.stdin(InputStream.nullInputStream());
                    }
                    Proc proc = procStarter.start();
                    outputHandler.attach(proc);
                    try {
                        exitCode = proc.join();
                        outputHandler.awaitTermination();
                    } catch (IOException | InterruptedException e) {
                        outputHandler.requestTermination();
                        try {
                            outputHandler.awaitTermination();
                        } catch (IOException | InterruptedException terminationFailure) {
                            if (terminationFailure != e) {
                                e.addSuppressed(terminationFailure);
                            }
                        }
                        throw e;
                    }
                }
            } finally {
                try {
                    if (outputHandler != null) {
                        outputHandler.close();
                    }
                } finally {
                    if (registered) {
                        ExecutionRegistry.unregister(run, invocationId);
                    }
                }
            }

            if (outputHandler.wasDeniedByApproval()) {
                exitCode = 1;
            }
            action.markCompleted(invocationId, exitCode);
            return exitCode;
        } finally {
            try {
                if (tempSetupScript != null) {
                    try {
                        tempSetupScript.delete();
                    } catch (IOException e) {
                        listener.getLogger()
                                .println(
                                        "[ai-agent] Warning: could not delete temp script: "
                                                + e.getMessage());
                    }
                }
            } finally {
                executionCustomization.cleanup(listener);
            }
        }
    }

    private static int executeAcpProcess(
            Launcher launcher,
            List<String> command,
            FilePath runDirectory,
            EnvVars procEnv,
            TaskListener listener,
            AgentOutputHandler outputHandler,
            ExecutionRegistry.LiveExecution liveExecution,
            Duration approvalTimeout,
            String prompt,
            AiAgentTypeHandler.AcpExecutionSpec acpExecution)
            throws IOException, InterruptedException {
        Proc proc =
                launcher.launch()
                        .cmds(command)
                        .pwd(runDirectory)
                        .envs(procEnv)
                        .readStdout()
                        .readStderr()
                        .writeStdin()
                        .quiet(true)
                        .start();
        outputHandler.attach(proc);

        InputStream stdout = proc.getStdout();
        InputStream stderr = proc.getStderr();
        OutputStream stdin = proc.getStdin();
        if (stdout == null || stderr == null || stdin == null) {
            proc.kill();
            throw new IOException("Jenkins launcher did not provide ACP process streams.");
        }

        StreamCopyThread stderrThread =
                new StreamCopyThread(
                        "ai-agent-acp-stderr",
                        stderr,
                        new NonClosingSynchronizedOutputStream(outputHandler),
                        false);
        stderrThread.start();
        try {
            AcpClientSession session =
                    new AcpClientSession(
                            proc, stdout, stdin, outputHandler, liveExecution, approvalTimeout);
            return session.execute(
                            runDirectory.getRemote(),
                            prompt,
                            acpExecution.getModel(),
                            acpExecution.getReasoningEffort())
                    ? 0
                    : 1;
        } finally {
            try {
                stdin.close();
            } finally {
                if (proc.isAlive()) {
                    proc.kill();
                }
                proc.joinWithTimeout(10, TimeUnit.SECONDS, listener);
                stderrThread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
    }

    private static String buildCombinedScript(
            String setupScript,
            Map<String, String> shellEnvironment,
            List<String> agentCommand,
            String commandOverride) {
        StringBuilder sb = new StringBuilder();
        appendShebangAwarePreamble(sb, setupScript, shellEnvironment);
        if (!commandOverride.isEmpty()) {
            String cmd = commandOverride;
            sb.append(cmd);
            if (!cmd.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("exec");
            for (String token : agentCommand) {
                sb.append(' ').append(shellQuote(token));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendShebangAwarePreamble(
            StringBuilder sb, String setupScript, Map<String, String> shellEnvironment) {
        String normalizedSetupScript = Util.fixNull(setupScript);
        if (normalizedSetupScript.startsWith("#!")) {
            int end = normalizedSetupScript.indexOf('\n');
            if (end < 0) {
                end = normalizedSetupScript.length();
            }
            sb.append(normalizedSetupScript, 0, end).append('\n');
            appendShellExports(sb, shellEnvironment);
            if (end < normalizedSetupScript.length()) {
                sb.append(normalizedSetupScript.substring(end + 1));
                if (!normalizedSetupScript.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            return;
        }
        appendShellExports(sb, shellEnvironment);
        sb.append(normalizedSetupScript);
        if (!normalizedSetupScript.isEmpty() && !normalizedSetupScript.endsWith("\n")) {
            sb.append('\n');
        }
    }

    private static void appendShellExports(StringBuilder sb, Map<String, String> shellEnvironment) {
        for (Map.Entry<String, String> entry : shellEnvironment.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            sb.append("export ")
                    .append(key)
                    .append('=')
                    .append(shellQuote(entry.getValue() == null ? "" : entry.getValue()))
                    .append('\n');
        }
    }

    /**
     * Writes the combined script to an agent-local temp area so the AI agent never sees it in the
     * project workspace.
     */
    private static FilePath writeTempScript(FilePath workspace, String combinedScript)
            throws IOException, InterruptedException {
        FilePath tempDir = AiAgentTempFiles.tempRoot(workspace);
        FilePath tempScript = tempDir.createTextTempFile("ai-agent-setup", ".sh", combinedScript);
        tempScript.chmod(0755);
        return tempScript;
    }

    /**
     * Builds the shell command to run the combined script, honoring a shebang line the same way the
     * Jenkins Shell build step does: if the script starts with {@code #!}, that interpreter is
     * used; otherwise {@code /bin/sh -xe} is used as the default.
     */
    private static List<String> buildShellCommand(String setupScript, FilePath tempScript) {
        if (setupScript.startsWith("#!")) {
            int end = setupScript.indexOf('\n');
            if (end < 0) end = setupScript.length();
            String shebangLine = setupScript.substring(0, end).trim();
            List<String> args = new ArrayList<>(Arrays.asList(Util.tokenize(shebangLine)));
            args.set(0, args.get(0).substring(2));
            args.add(tempScript.getRemote());
            return args;
        }
        return List.of("/bin/sh", "-xe", tempScript.getRemote());
    }

    private static String shellQuote(String s) {
        if (s.isEmpty()) {
            return "''";
        }
        if (s.matches("[a-zA-Z0-9_./:=@-]+")) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static List<String> buildWindowsCommand(List<String> command) {
        return new ArgumentListBuilder().add(command).toWindowsCommand().toList();
    }

    private static FilePath resolveRunDirectory(FilePath workspace, String workDirValue) {
        String trimmed = Util.fixNull(workDirValue).trim();
        if (trimmed.isEmpty()) {
            return workspace;
        }
        return workspace.child(trimmed);
    }

    /**
     * Prevents one stream pump from closing the shared output handler while the other stream is
     * still active. The Jenkins launcher may close stdout and stderr independently.
     */
    private static final class NonClosingSynchronizedOutputStream extends OutputStream {
        private final OutputStream delegate;

        NonClosingSynchronizedOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (delegate) {
                delegate.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (delegate) {
                delegate.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (delegate) {
                delegate.flush();
            }
        }

        @Override
        public void close() {}
    }

    static final class AgentOutputHandler extends LineTransformationOutputStream {
        private final OutputStream logger;
        private final BufferedWriter rawWriter;
        private final ExecutionRegistry.LiveExecution liveExecution;
        private final boolean approvalsEnabled;
        private final Duration approvalTimeout;
        private final AiAgentLogFormat logFormat;
        private final Pattern sensitivePattern;
        private final AiAgentLogParser.ParseState parseState = new AiAgentLogParser.ParseState();
        private final AtomicLong lineCounter = new AtomicLong();
        private final Object terminationLock = new Object();
        private volatile Proc proc;
        private volatile boolean terminationRequested;
        private volatile Thread terminationThread;
        private volatile IOException terminationFailure;
        private volatile boolean deniedByApproval;

        AgentOutputHandler(
                OutputStream logger,
                File rawLogFile,
                ExecutionRegistry.LiveExecution liveExecution,
                boolean approvalsEnabled,
                Duration approvalTimeout,
                AiAgentLogFormat logFormat,
                List<String> sensitiveValues)
                throws IOException {
            this.logger = logger;
            this.rawWriter =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    Files.newOutputStream(rawLogFile.toPath()),
                                    StandardCharsets.UTF_8));
            this.liveExecution = liveExecution;
            this.approvalsEnabled = approvalsEnabled;
            this.approvalTimeout = approvalTimeout;
            this.logFormat = logFormat;
            this.sensitivePattern =
                    SecretPatterns.getAggregateSecretPattern(
                            expandSensitiveValues(sensitiveValues));
        }

        void attach(Proc proc) {
            synchronized (terminationLock) {
                this.proc = proc;
                startTerminationLocked();
            }
        }

        void requestTermination() {
            liveExecution.cancelPendingApprovals("agent process terminated");
            synchronized (terminationLock) {
                terminationRequested = true;
                startTerminationLocked();
            }
        }

        void awaitTermination() throws IOException, InterruptedException {
            Thread thread;
            synchronized (terminationLock) {
                thread = terminationThread;
            }
            if (thread == null) {
                return;
            }
            thread.join();
            IOException failure = terminationFailure;
            if (failure != null) {
                throw failure;
            }
        }

        private void startTerminationLocked() {
            if (!terminationRequested || proc == null || terminationThread != null) {
                return;
            }
            Proc processToKill = proc;
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    processToKill.kill();
                                } catch (IOException e) {
                                    terminationFailure = e;
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    terminationFailure =
                                            new IOException(
                                                    "Interrupted while terminating agent process.",
                                                    e);
                                }
                            },
                            "ai-agent-process-terminator");
            thread.setDaemon(true);
            terminationThread = thread;
            thread.start();
        }

        boolean wasDeniedByApproval() {
            return deniedByApproval;
        }

        @Override
        protected synchronized void eol(byte[] b, int len) throws IOException {
            String line = new String(b, 0, len, StandardCharsets.UTF_8);
            recordLine(line);
        }

        synchronized void recordLine(String rawLine) throws IOException {
            String line = maskSensitiveValues(rawLine);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }

            logger.write(line.getBytes(StandardCharsets.UTF_8));
            logger.write('\n');
            logger.flush();

            rawWriter.write(line);
            rawWriter.newLine();
            rawWriter.flush();

            if (!approvalsEnabled) {
                return;
            }

            long id = lineCounter.incrementAndGet();
            for (AiAgentLogParser.ParsedLine parsedLine :
                    AiAgentLogParser.parseLines(id, line, logFormat)) {
                if (!parseState.shouldEmit(parsedLine)) {
                    continue;
                }
                if (!parsedLine.isToolCall()) {
                    continue;
                }

                ExecutionRegistry.PendingApproval pending =
                        liveExecution.createPendingApproval(
                                parsedLine.getToolCallIdOrGenerated(),
                                parsedLine.getToolName(),
                                parsedLine.getSummary());
                writeStatus(
                        "Approval required: "
                                + pending.getToolName()
                                + " ("
                                + pending.getToolCallId()
                                + ")");

                ExecutionRegistry.ApprovalDecision decision =
                        liveExecution.awaitDecision(pending, approvalTimeout);
                if (!decision.isApproved()) {
                    deniedByApproval = true;
                    try {
                        writeStatus("Approval denied: " + decision.getReason());
                    } finally {
                        requestTermination();
                    }
                    return;
                }
                writeStatus("Approval granted: " + pending.getToolName());
            }
        }

        private String maskSensitiveValues(String value) {
            if (sensitivePattern.pattern().isEmpty()) {
                return value;
            }
            return sensitivePattern.matcher(value).replaceAll("****");
        }

        private static List<String> expandSensitiveValues(List<String> sensitiveValues) {
            List<String> expanded = new ArrayList<>();
            for (String sensitiveValue : sensitiveValues) {
                if (sensitiveValue == null || sensitiveValue.isEmpty()) {
                    continue;
                }
                expanded.add(sensitiveValue);
                for (String line : sensitiveValue.split("\\R")) {
                    if (!line.isEmpty()) {
                        expanded.add(line);
                    }
                }
            }
            return expanded;
        }

        synchronized void writeStatus(String message) throws IOException {
            logger.write(("[ai-agent] " + message + "\n").getBytes(StandardCharsets.UTF_8));
            logger.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            rawWriter.close();
        }
    }
}
