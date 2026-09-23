package com.example.lms.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanNameLogRedactionContractTest {

    @Test
    void beanAndHandlerDiagnosticsUseHashAndLengthOnly() throws Exception {
        String braveTimeout = read("main/java/ai/abandonware/nova/orch/web/brave/BraveRestTemplateTimeoutOverrideInstaller.java");
        String braveQps = read("main/java/ai/abandonware/nova/orch/web/brave/BraveAdaptiveQpsInstaller.java");
        String cancelShield = read("main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorServicePostProcessor.java");
        String wiring = read("main/java/com/example/lms/boot/WiringPrecheckRunner.java");
        String retrieval = read("main/java/com/example/lms/service/rag/handler/AbstractRetrievalHandler.java");

        assertFalse(braveTimeout.contains("bean='{}'"));
        assertFalse(braveQps.contains("bean='{}'"));
        assertFalse(cancelShield.contains("bean '{}'"));
        assertFalse(wiring.contains("bean='{}'"));
        assertFalse(wiring.contains("-> {}\", label, type.getName(), beans.keySet()"));
        assertFalse(retrieval.contains("Handler '{}' failed"));

        assertTrue(braveTimeout.contains("beanHash={} beanLength={}"));
        assertTrue(braveQps.contains("beanHash={} beanLength={}"));
        assertTrue(cancelShield.contains("beanHash={} beanLength={}"));
        assertTrue(wiring.contains("beanHash={} beanLength={}"));
        assertTrue(wiring.contains("count={} beanSummaries={}"));
        assertTrue(wiring.contains("hash=\" + SafeRedactor.hashValue(value) + \",length=\" + lengthOf(value)"));
        assertTrue(retrieval.contains("Handler handlerHash={} handlerLength={} failed"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
