package com.example.lms.trace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTagNormalizerTest {

    @Test
    void arraySignalsCountAsRetrievalHits() {
        var tags = FailureTagNormalizer.normalize(Map.of(
                "finalVectorTopK", new String[]{"doc-a"},
                "finalWebTopK", new String[]{"web-a"},
                "rag.compress.applied", true,
                "guard.degradedToEvidence", true), null, null);

        assertTrue(tags.contains("VEC_HIT"));
        assertTrue(tags.contains("WEB_HIT"));
        assertTrue(tags.contains("AFR_TR"));
    }

    @Test
    void arrayLengthProbeCatchIsNarrow() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/FailureTagNormalizer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("catch (Exception ignore)"));
        assertTrue(source.contains("catch (IllegalArgumentException ignore)"));
        assertTrue(source.contains("TraceStore.put(\"trace.failureTag.suppressed.arrayLength.errorType\""));
    }
}
