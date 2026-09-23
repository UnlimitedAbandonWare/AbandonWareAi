package com.acme.aicore.adapters.ranking;

import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.SearchBundle;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpAwareWeightedRrfRankingTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void fallbackTraceKeysRemainRedactedWhenParamsThrow() {
        MpAwareWeightedRrfRanking ranking =
                new MpAwareWeightedRrfRanking(null, new WeightedRrfRanking(), 1.15d, 1.0d, 200);
        SearchBundle bundle = new SearchBundle("web", List.of(
                new SearchBundle.Doc("doc-1", "private title", "private snippet", "https://example.test", "")));

        List<RankedDoc> out = ranking.fuseAndRank(List.of(bundle), new ThrowingParams()).block();

        assertEquals(1, out == null ? 0 : out.size());
        assertEquals(1L, TraceStore.get("ranking.mpAware.rrfKFallback.count"));
        assertEquals("IllegalStateException", TraceStore.get("ranking.mpAware.rrfKFallback.errorType"));
        String snapshot = TraceStore.getAll().toString();
        assertFalse(snapshot.contains("private title"));
        assertFalse(snapshot.contains("raw private"));
    }

    @Test
    void catchBlocksUseHarmonyRecognizedTraceSuppressedHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/acme/aicore/adapters/ranking/MpAwareWeightedRrfRanking.java"));

        assertTrue(source.contains("traceSuppressed(\"statsFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"rrfKFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"windowFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"weightFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"labelLogFallback\", ex);"));
        assertFalse(source.contains("traceFallback("));
    }

    private static final class ThrowingParams extends RankingParams {
        @Override
        public int rrfK() {
            throw new IllegalStateException("raw private rrfK");
        }
    }
}
