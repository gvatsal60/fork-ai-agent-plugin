package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.TaskListener;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

class AiAgentExecutionCustomizationTest {

    @Test
    void cleanup_restoresInterruptAndRunsRemainingActions() {
        Thread.interrupted();
        AtomicBoolean secondActionRan = new AtomicBoolean();
        AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
        customization.addCleanupAction(
                () -> {
                    throw new InterruptedException("interrupted cleanup");
                });
        customization.addCleanupAction(() -> secondActionRan.set(true));

        try {
            customization.cleanup(TaskListener.NULL);

            assertTrue(secondActionRan.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
