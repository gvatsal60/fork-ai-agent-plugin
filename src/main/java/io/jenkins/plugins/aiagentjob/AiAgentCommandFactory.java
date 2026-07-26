package io.jenkins.plugins.aiagentjob;

import hudson.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the CLI command line for the selected agent handler and parses environment variables. */
final class AiAgentCommandFactory {
    private AiAgentCommandFactory() {}

    static List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        AiAgentTypeHandler handler = config.getAgent();
        if (handler == null) {
            throw new IllegalStateException("No agent handler configured.");
        }
        handler.validateExecution(config);
        List<String> command = new ArrayList<>(handler.buildDefaultCommand(config, prompt));

        String extraArgs = Util.fixEmptyAndTrim(config.getExtraArgs());
        if (extraArgs != null) {
            Collections.addAll(command, Util.tokenize(extraArgs));
        }
        return applyExecutablePath(command, config.getExecutablePath());
    }

    static List<String> applyExecutablePath(List<String> command, String executablePath) {
        String executable = Util.fixEmptyAndTrim(executablePath);
        if (executable == null) {
            return command;
        }
        if (command.isEmpty()) {
            throw new IllegalStateException("Agent command is empty.");
        }
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, executable);
        return resolved;
    }

    static Map<String, String> parseEnvironmentVariables(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return values;
        }

        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1);
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }
}
