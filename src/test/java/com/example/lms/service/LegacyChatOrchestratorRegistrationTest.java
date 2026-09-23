package com.example.lms.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyChatOrchestratorRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChatOrchestrator.class, ChatOrchestratorPatch.class);

    @Test
    void legacyOrchestratorsAreAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatOrchestrator.class);
            assertThat(context).doesNotHaveBean(ChatOrchestratorPatch.class);
        });
    }

    @Test
    void legacyRegistrationIsExplicitOptInAndCanonicalFacadeOwnsRuntimeFlow() throws Exception {
        String orchestrator = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatOrchestrator.java"));
        String patch = Files.readString(
                Path.of("main/java/com/example/lms/service/patch/ChatOrchestratorPatch.java"));
        String facade = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatService.java"));

        assertTrue(orchestrator.contains(
                "@ConditionalOnProperty(name = \"legacy.chat-orchestrator.enabled\", "
                        + "havingValue = \"true\", matchIfMissing = false)"));
        assertTrue(patch.contains(
                "@ConditionalOnProperty(name = \"legacy.chat-orchestrator-patch.enabled\", "
                        + "havingValue = \"true\", matchIfMissing = false)"));
        assertTrue(facade.contains("private final ChatWorkflow workflow;"));
        assertTrue(facade.contains("return workflow.continueChat(req);"));
    }
}
