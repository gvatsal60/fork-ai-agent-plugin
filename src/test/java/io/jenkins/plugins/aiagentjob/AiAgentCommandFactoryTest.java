package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.codex.CodexAgentHandler;
import io.jenkins.plugins.aiagentjob.cursor.CursorAgentHandler;
import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class AiAgentCommandFactoryTest {

    private static AiAgentBuilder createProject(AiAgentTypeHandler handler) {
        AiAgentBuilder project = new AiAgentBuilder();
        project.setAgent(handler);
        return project;
    }

    private static List<AiAgentTypeHandler> allHandlers() {
        List<AiAgentTypeHandler> handlers = new ArrayList<>();
        handlers.add(new ClaudeCodeAgentHandler());
        handlers.add(new CodexAgentHandler());
        handlers.add(new CursorAgentHandler());
        handlers.add(new OpenCodeAgentHandler());
        handlers.add(new GeminiCliAgentHandler());
        return handlers;
    }

    // ======================== Claude Code Command Tests ========================

    @Test
    void claudeCode_basicCommand() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setPrompt("Hello world");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "Hello world");

        assertEquals("npx", cmd.get(0), "Should start with npx");
        assertTrue(cmd.contains("-y"), "Should have -y");
        assertTrue(cmd.contains("@anthropic-ai/claude-code"), "Should have claude-code package");
        assertTrue(cmd.contains("-p"), "Should have -p flag");
        assertTrue(cmd.contains("Hello world"), "Should have prompt");
        assertTrue(cmd.contains("--output-format=stream-json"), "Should have stream-json output");
        assertTrue(cmd.contains("--verbose"), "Should have --verbose");
        assertFalse(
                cmd.contains("--input-format=stream-json"),
                "Should NOT have --input-format=stream-json (not interactive)");
    }

    @Test
    void claudeCode_yoloMode() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertTrue(
                cmd.contains("--dangerously-skip-permissions"),
                "Should have --dangerously-skip-permissions");
        assertFalse(
                cmd.contains("--permission-mode=default"),
                "Should NOT have --permission-mode=default");
    }

    @Test
    void claudeCode_approvalsMode() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setRequireApprovals(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertTrue(
                cmd.contains("--permission-mode=default"), "Should have --permission-mode=default");
        assertFalse(
                cmd.contains("--dangerously-skip-permissions"),
                "Should NOT have --dangerously-skip-permissions");
    }

    @Test
    void claudeCode_withModel() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setModel("claude-opus-4");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("claude-opus-4", cmd.get(modelIdx + 1));
    }

    @Test
    void claudeCode_withReasoningEffort() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setReasoningEffort("xhigh");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int effortIdx = cmd.indexOf("--effort");
        assertTrue(effortIdx >= 0, "Should have --effort");
        assertEquals("xhigh", cmd.get(effortIdx + 1));
    }

    // ======================== Codex Command Tests ========================

    @Test
    void codex_basicCommand() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "fix the bug");

        assertEquals("codex", cmd.get(0));
        assertTrue(cmd.indexOf("exec") > 0, "Should run codex exec");
        assertTrue(cmd.contains("--json"), "Should have --json for JSONL output");
        assertTrue(
                cmd.contains("--skip-git-repo-check"),
                "Should have --skip-git-repo-check for CI environments");
        assertTrue(cmd.contains("fix the bug"), "Should have prompt at end");
    }

    @Test
    void codex_yoloMode() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(
                cmd.contains("--dangerously-bypass-approvals-and-sandbox"),
                "Should have --dangerously-bypass-approvals-and-sandbox");
        assertFalse(cmd.contains("--sandbox"), "Should NOT have --sandbox");
        assertFalse(
                cmd.contains("--ask-for-approval"),
                "Should NOT have --ask-for-approval in yolo mode");
    }

    @Test
    void codex_defaultMode() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setYoloMode(false);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--sandbox"), "Should have --sandbox");
        assertTrue(cmd.contains("workspace-write"), "Should have workspace-write");
        int approvalIdx = cmd.indexOf("--ask-for-approval");
        assertTrue(approvalIdx > 0, "Should have --ask-for-approval for headless execution");
        assertEquals("never", cmd.get(approvalIdx + 1));
        assertTrue(cmd.indexOf("--sandbox") < cmd.indexOf("exec"), "--sandbox is a global flag");
        assertTrue(approvalIdx < cmd.indexOf("exec"), "--ask-for-approval is a global flag");
        assertFalse(cmd.contains("--full-auto"), "Should not use removed --full-auto flag");
    }

    @Test
    void codex_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("does not expose"));
    }

    @Test
    void codex_withModel() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setModel("o3");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("o3", cmd.get(modelIdx + 1));
    }

    @Test
    void codex_withReasoningEffort() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setReasoningEffort("high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int configIdx = cmd.indexOf("-c");
        assertTrue(configIdx > 0, "Should have config override");
        assertEquals("model_reasoning_effort=\"high\"", cmd.get(configIdx + 1));
        assertTrue(configIdx < cmd.indexOf("exec"), "Codex reasoning effort is a global config");
    }

    @Test
    void codex_promptIsLastArgument() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "refactor this");

        assertEquals("refactor this", cmd.get(cmd.size() - 1), "Prompt should be last argument");
    }

    @Test
    void codex_additionalArgsArePlacedInCodexScopes() {
        CodexAgentHandler codex = new CodexAgentHandler();
        codex.setAdditionalGlobalArgs("--search --profile ci");
        codex.setAdditionalExecArgs("--ephemeral --ignore-user-config --color never");
        AiAgentBuilder project = createProject(codex);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int execIdx = cmd.indexOf("exec");
        int promptIdx = cmd.indexOf("test");
        assertTrue(cmd.indexOf("--search") > 0 && cmd.indexOf("--search") < execIdx);
        assertTrue(cmd.indexOf("--profile") > 0 && cmd.indexOf("--profile") < execIdx);
        assertEquals("ci", cmd.get(cmd.indexOf("--profile") + 1));
        assertTrue(cmd.indexOf("--ephemeral") > execIdx && cmd.indexOf("--ephemeral") < promptIdx);
        assertTrue(
                cmd.indexOf("--ignore-user-config") > execIdx
                        && cmd.indexOf("--ignore-user-config") < promptIdx);
        assertEquals("never", cmd.get(cmd.indexOf("--color") + 1));
    }

    // ======================== Cursor Agent Command Tests ========================

    @Test
    void cursorAgent_basicCommand() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "analyze code");

        assertEquals("agent", cmd.get(0));
        assertTrue(cmd.contains("-p"), "Should have -p for print mode");
        assertTrue(
                cmd.contains("--output-format=stream-json"),
                "Should have --output-format=stream-json");
        assertTrue(cmd.contains("--trust"), "Should have --trust for headless mode");
        assertTrue(cmd.contains("--approve-mcps"), "Should have --approve-mcps for headless mode");
        assertTrue(cmd.contains("analyze code"), "Should have prompt");
    }

    @Test
    void cursorAgent_yoloMode() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--yolo"), "Should have --yolo");
    }

    @Test
    void cursorAgent_withModel() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setModel("sonnet-4-thinking");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("sonnet-4-thinking", cmd.get(modelIdx + 1));
    }

    // ======================== OpenCode Command Tests ========================

    @Test
    void openCode_basicCommand() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "explain this");

        assertEquals("opencode", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertTrue(cmd.contains("--format"), "Should have --format");
        assertTrue(cmd.contains("json"), "Should have json");
        assertTrue(cmd.contains("explain this"), "Should have prompt");
    }

    @Test
    void openCode_withModel() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setModel("anthropic/claude-sonnet-4");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("anthropic/claude-sonnet-4", cmd.get(modelIdx + 1));
    }

    @Test
    void openCode_withReasoningEffort() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setReasoningEffort("max");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int variantIdx = cmd.indexOf("--variant");
        assertTrue(variantIdx >= 0, "Should have --variant");
        assertEquals("max", cmd.get(variantIdx + 1));
    }

    @Test
    void openCode_acpExecutionMapsRunOptionsToSessionConfig() {
        OpenCodeAgentHandler handler = new OpenCodeAgentHandler();
        AiAgentBuilder project = createProject(handler);
        project.setModel("opencode/provider-model");
        project.setReasoningEffort("high");
        project.setExtraArgs("--model override/model --variant=xhigh --format json --pure");

        AiAgentTypeHandler.AcpExecutionSpec execution = handler.buildAcpExecution(project);

        assertEquals(List.of("opencode", "acp", "--pure"), execution.getCommand());
        assertEquals("override/model", execution.getModel());
        assertEquals("xhigh", execution.getReasoningEffort());
    }

    // ======================== Gemini CLI Command Tests ========================

    @Test
    void geminiCli_basicCommand() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "summarize project");

        assertEquals("gemini", cmd.get(0));
        assertTrue(cmd.contains("-p"), "Should have -p for prompt");
        assertTrue(cmd.contains("--output-format"), "Should have --output-format");
        assertTrue(cmd.contains("stream-json"), "Should have stream-json");
        assertTrue(cmd.contains("summarize project"), "Should have prompt");
    }

    @Test
    void geminiCli_yoloMode() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--yolo"), "Should have --yolo");
    }

    @Test
    void geminiCli_withApprovals() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setRequireApprovals(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--approval-mode"), "Should have --approval-mode");
        assertTrue(cmd.contains("default"), "Should have default");
    }

    @Test
    void geminiCli_withModel() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setModel("gemini-2.5-flash");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("-m");
        assertTrue(modelIdx >= 0, "Should have -m");
        assertEquals("gemini-2.5-flash", cmd.get(modelIdx + 1));
    }

    // ======================== Extra Args Tests ========================

    @Test
    void extraArgs_appendedToCommand() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExtraArgs("--max-budget-usd 5 --effort high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--max-budget-usd"), "Should contain --max-budget-usd");
        assertTrue(cmd.contains("5"), "Should contain 5");
        assertTrue(cmd.contains("--effort"), "Should contain --effort");
        assertTrue(cmd.contains("high"), "Should contain high");
    }

    @Test
    void extraArgs_emptyDoesNotAddTokens() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExtraArgs("   ");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");
        int verboseIdx = cmd.indexOf("--verbose");
        assertEquals(
                verboseIdx, cmd.size() - 1, "Last regular arg should be the last element or close");
    }

    // ======================== Environment Variable Parsing
    // ========================

    @Test
    void parseEnvironmentVariables_basic() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables("KEY1=value1\nKEY2=value2");
        assertEquals(2, vars.size());
        assertEquals("value1", vars.get("KEY1"));
        assertEquals("value2", vars.get("KEY2"));
    }

    @Test
    void parseEnvironmentVariables_handlesCommentsAndBlanks() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables(
                        "# comment\nKEY=val\n\n  # another comment\n  ");
        assertEquals(1, vars.size());
        assertEquals("val", vars.get("KEY"));
    }

    @Test
    void parseEnvironmentVariables_handlesEqualsInValue() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables(
                        "DATABASE_URL=postgres://user:pass@host/db?sslmode=require");
        assertEquals(1, vars.size());
        assertEquals("postgres://user:pass@host/db?sslmode=require", vars.get("DATABASE_URL"));
    }

    @Test
    void parseEnvironmentVariables_handlesNullAndEmpty() {
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables(null).isEmpty());
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables("").isEmpty());
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables("   ").isEmpty());
    }

    @Test
    void parseEnvironmentVariables_handlesWindowsLineEndings() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables("A=1\r\nB=2\r\n");
        assertEquals(2, vars.size());
        assertEquals("1", vars.get("A"));
        assertEquals("2", vars.get("B"));
    }

    // ======================== Model Without Value ========================

    @Test
    void allAgents_noModelByDefault() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");
            assertFalse(
                    cmd.contains("--model") || cmd.contains("-m"),
                    "Agent " + handler.getId() + " should not add --model when empty");
        }
    }

    @Test
    void allAgents_havePromptInCommand() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            List<String> cmd =
                    AiAgentCommandFactory.buildDefaultCommand(
                            project, "unique-prompt-" + handler.getId());
            assertTrue(
                    cmd.contains("unique-prompt-" + handler.getId()),
                    "Agent " + handler.getId() + " should have prompt in command");
        }
    }
}
