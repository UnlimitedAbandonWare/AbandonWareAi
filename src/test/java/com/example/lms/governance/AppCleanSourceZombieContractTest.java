package com.example.lms.governance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AppCleanSourceZombieContractTest {

    @Test
    void appCleanSourceDoesNotShipPlaceholderOcrRetrievalHandler() {
        assertFalse(
                Files.exists(Path.of("app/src/main/java_clean/com/example/lms/service/rag/handler/OcrRetrievalHandler.java")),
                "app java_clean must not ship a fake OCR handler that emits placeholder evidence");
    }

    @Test
    void appCleanSourceDoesNotKeepHardExcludedLegacyFusionStubs() {
        assertFalse(
                Files.exists(Path.of("app/src/main/java_clean/com/example/lms/service/rag/fusion/WeightedRRF.java")),
                "app java_clean must not keep a hard-excluded legacy RRF stub that is shadowed by root runtime fusion");
        assertFalse(
                Files.exists(Path.of("app/src/main/java_clean/com/example/lms/service/rag/fusion/RerankCanonicalizer.java")),
                "app java_clean must not keep a hard-excluded legacy canonicalizer stub that is shadowed by root runtime fusion");
    }

    @Test
    void appCleanSourceDoesNotKeepOrphanedLegacyFusionHelpers() {
        assertFalse(
                Files.exists(Path.of("app/src/main/java_clean/com/example/lms/service/rag/fusion/DeltaProjectionBooster.java")),
                "app java_clean must not keep an orphaned delta-projection fusion stub after legacy RRF removal");
        assertFalse(
                Files.exists(Path.of("app/src/main/java_clean/com/example/lms/service/rag/fusion/PlattIsotonicCalibrator.java")),
                "app java_clean must not keep an orphaned Platt/isotonic calibrator helper after legacy RRF removal");
    }

    @Test
    void appCleanSourceDoesNotKeepUnreferencedRagCompatibilityAdapters() {
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/fusion/BodeClamp.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/fusion/CvarAggregator.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/fusion/ScoreCalibrator.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/fusion/WeightedPowerMeanFuser.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/model/ContextSlice.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/overdrive/AngerOverdriveNarrower.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/overdrive/ExtremeZSystemHandler.java");
        assertAbsent("app/src/main/java_clean/com/example/lms/service/rag/planner/SelfAskPlanner.java");
    }

    private static void assertAbsent(String path) {
        assertFalse(
                Files.exists(Path.of(path)),
                "app java_clean must not keep unreferenced RAG compatibility adapter: " + path);
    }
}
