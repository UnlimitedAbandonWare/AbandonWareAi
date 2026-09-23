package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagUtilityFailSoftBreadcrumbTest {

    @Test
    void utilityFallbacksLeaveStageBreadcrumbs() throws Exception {
        assertStage(read("main/java/com/example/lms/service/rag/energy/ContextEnergyModel.java"),
                "ContextEnergyModel", "recencyYear.parse");
        String contradiction = read("main/java/com/example/lms/service/rag/energy/ContradictionScorer.java");
        assertStage(contradiction, "ContradictionScorer", "parseDoubles");
        assertStage(contradiction, "ContradictionScorer", "parseNumber0to1");
        assertStage(read("main/java/com/example/lms/service/rag/extract/PageContentScraper.java"),
                "PageContentScraper", "fetchText");
        assertStage(read("main/java/com/example/lms/service/rag/fusion/DedupUtil.java"),
                "DedupUtil", "canonicalUrl");
        assertStage(read("main/java/com/example/lms/service/rag/fusion/RerankCanonicalizer.java"),
                "RerankCanonicalizer", "canonicalKey");
        assertStage(read("main/java/com/abandonware/ai/service/rag/fusion/RerankCanonicalizer.java"),
                "RerankCanonicalizer", "canonicalKey");
        assertStage(read("main/java/com/example/lms/service/rag/fusion/RrfFusion.java"),
                "RrfFusion", "canonicalizeUrl");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void assertStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }
}
