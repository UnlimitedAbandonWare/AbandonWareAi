package com.example.lms.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class FlowJoinerFacadeTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("flow.joiner.impl");
    }

    @Test
    void routerModeUsesBuiltInRouterCompatibleSequence() {
        System.setProperty("flow.joiner.impl", "router");

        assertArrayEquals(
                new String[]{"plan", "retrieve", "critic_coverage", "synth", "send_message"},
                FlowJoinerFacade.sequence(true, true));
    }

    @Test
    void lmsModeFallsBackToSameRouterCompatibleSequence() {
        System.setProperty("flow.joiner.impl", "lms");

        assertArrayEquals(
                new String[]{"plan", "retrieve_fallback", "critic_coverage", "synth", "send_outbox"},
                FlowJoinerFacade.sequence(false, false));
    }

    @Test
    void fallbackPathsLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/flow/FlowJoinerFacade.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"flowJoiner.sequence\", t);"));
        assertTrue(source.contains("traceSuppressed(\"flowJoiner.primary\", t);"));
        assertTrue(source.contains("TraceStore.put(\"flow.joiner.suppressed.\" + safeStage, true);"));
    }
}
