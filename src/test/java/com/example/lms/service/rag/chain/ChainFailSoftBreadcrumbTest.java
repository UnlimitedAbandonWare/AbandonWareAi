package com.example.lms.service.rag.chain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainFailSoftBreadcrumbTest {

    @Test
    void chainFailSoftFallbacksLeaveStageBreadcrumbs() throws Exception {
        String image = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/chain/ImagePromptGroundingHandler.java"));
        String runner = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/chain/impl/ChainRunner.java"));
        String context = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/chain/impl/DefaultChainContext.java"));

        assertStage(image, "ImagePromptGroundingHandler", "groundPrompt");
        assertStage(image, "ImagePromptGroundingHandler", "groundedPromptHash");
        assertStage(runner, "ChainRunner", "recordFaultMasking");
        assertStage(runner, "ChainRunner", "recordNightmareBreaker");
        assertStage(runner, "ChainRunner", "emitFailSoftDebugEvent");
        assertStage(runner, "ChainRunner", "shortHash");
        assertStage(context, "DefaultChainContext", "imageMeta.propagation");
    }

    private static void assertStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }
}
