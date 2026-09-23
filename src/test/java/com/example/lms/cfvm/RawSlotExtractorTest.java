package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawSlotExtractorTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void extractorIsRegisteredAsSpringBeanForCfvmDiagnostics() {
        assertNotNull(RawSlotExtractor.class.getAnnotation(Component.class));
    }

    @Test
    void signatureIncludesRedactedFailureCauseWithoutRawQuery() {
        Map<String, Object> base = Map.of(
                "plan.id", "safe_autorun",
                "retrieval.order", "WEB,VECTOR",
                "raw.query", "do not include this text");
        Map<String, Object> contradiction = Map.of(
                "plan.id", "safe_autorun",
                "retrieval.order", "WEB,VECTOR",
                "extremez.risk.primaryCause", "evidence conflict",
                "extremez.activation.reason", "contradiction",
                "learning.validation.decision", "rejected",
                "learning.error.hotspot", "evidence_gate",
                "ownerToken", "owner-token-123",
                "source.key", "secret-source-key",
                "snippet", "sensitive snippet text",
                "raw.query", "do not include this text");

        String signature = RawSlotExtractor.signature(contradiction);

        assertTrue(signature.contains("extremePrimary=evidence_conflict"));
        assertTrue(signature.contains("extremeReason=evidence_conflict"));
        assertTrue(signature.contains("validation=rejected"));
        assertTrue(signature.contains("hotspot=evidence_gate"));
        assertTrue(!signature.contains("do not include this text"));
        assertTrue(!signature.contains("owner-token-123"));
        assertTrue(!signature.contains("secret-source-key"));
        assertTrue(!signature.contains("sensitive snippet text"));
        assertNotEquals(RawSlotExtractor.patternIdFromTrace(base), RawSlotExtractor.patternIdFromTrace(contradiction));
    }

    @Test
    void signatureIncludesCfvmJbCbAndBoltzmannSlots() {
        Map<String, Object> trace = Map.of(
                "cfvm.jb.score", 0.75d,
                "cfvm.cb.score", 0.25d,
                "cfvm.rawBuffer.boltzmannTemp", 0.5d);

        String signature = RawSlotExtractor.signature(trace);

        assertTrue(signature.contains("jb=0.75"));
        assertTrue(signature.contains("cb=0.25"));
        assertTrue(signature.contains("boltzTemp=0.5"));
    }

    @Test
    void numericSignatureHelpersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/cfvm/RawSlotExtractor.java"))
                .replace("\r\n", "\n");

        assertTrue(!source.contains("catch (Exception ignore) {\n            return safe(value);\n        }"));
        assertTrue(source.contains("catch (NumberFormatException ignore) {\n            traceSuppressed(\"cfvm.slot.bucket\", ignore);\n            return safe(value);\n        }"));
        assertTrue(source.contains("catch (NumberFormatException ignore) {\n            traceSuppressed(\"cfvm.slot.numberLabel\", ignore);\n            return safe(value);\n        }"));
    }

    @Test
    void invalidNumericSlotsEmitStableReasonCodeWithoutRawValue() {
        String raw = "private numeric slot ownerToken=fake-token";

        RawSlotExtractor.signature(Map.of(
                "retrieval.web.k", raw,
                "cfvm.jb.score", raw));

        assertEquals("invalid_number", TraceStore.get("cfvm.slot.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
    }

    @Test
    void suppressedTraceIncludesSafeAggregateStageWithoutRawSecret() throws Exception {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method method = RawSlotExtractor.class.getDeclaredMethod(
                "traceSuppressed", String.class, RuntimeException.class);
        method.setAccessible(true);

        method.invoke(null, "cfvm.slot.bucket " + secret, new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("cfvm.slot.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.slot.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("cfvm.slot.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
    }

    @Test
    void emptyTraceEmitsExplicitEmptyAndZeroPatternBreadcrumbs() {
        assertNull(RawSlotExtractor.signature(Map.of()));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.signature.empty"));

        TraceStore.clear();

        assertEquals(0L, RawSlotExtractor.patternIdFromTrace(Map.of()));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.signature.empty"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.patternId.zero"));
    }
}
