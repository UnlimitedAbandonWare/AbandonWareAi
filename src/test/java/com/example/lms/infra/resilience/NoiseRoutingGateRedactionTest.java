package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseRoutingGateRedactionTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(NoiseRoutingGate.PROP_ENABLED);
        System.clearProperty(NoiseRoutingGate.PROP_DETERMINISTIC);
        TraceStore.clear();
    }

    @Test
    void arbitraryGateKeyDoesNotBecomeRawTraceKeySegment() {
        System.setProperty(NoiseRoutingGate.PROP_ENABLED, "true");
        System.setProperty(NoiseRoutingGate.PROP_DETERMINISTIC, "true");
        String rawGateKey = "ownertoken " + "sk-" + "12345678901234567890";

        NoiseRoutingGate.decideEscape(rawGateKey, 1.0d, null);

        String rendered = String.valueOf(TraceStore.getAll()).toLowerCase(Locale.ROOT);
        assertFalse(rendered.contains("ownertoken"), rendered);
        assertFalse(rendered.contains("sk-" + "12345678901234567890"), rendered);
        assertTrue(rendered.contains("orch.noisegate.hash_"), rendered);
    }

    @Test
    void stableGateKeyKeepsReadableTraceSegment() {
        System.setProperty(NoiseRoutingGate.PROP_ENABLED, "true");
        System.setProperty(NoiseRoutingGate.PROP_DETERMINISTIC, "true");

        NoiseRoutingGate.decideEscape("qtx.compression", 1.0d, null);

        assertEquals(Boolean.TRUE, TraceStore.get("orch.noiseGate.qtx.compression.escape"));
    }

    @Test
    void failSoftNoiseGatePathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/infra/resilience/NoiseRoutingGate.java"));

        assertTrue(source.contains("traceSuppressed(\"noiseGate.trace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"noiseGate.decide\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"orch.noiseGate.suppressed.\" + safeStage, true);"));
    }

    @Test
    void supplementaryBoundaryDoesNotAliasQuestionMarkSeed() throws Exception {
        System.setProperty(NoiseRoutingGate.PROP_ENABLED, "true");
        System.setProperty(NoiseRoutingGate.PROP_DETERMINISTIC, "true");
        String prefix = "x".repeat(199);
        String supplementary = prefix + "\uD83D\uDE00" + "tail";
        String replacement = prefix + "?" + "tail";

        String safeSupplementary = invokeSafe(supplementary);

        GuardContext supplementaryContext = new GuardContext();
        supplementaryContext.setUserQuery(supplementary);
        GuardContext replacementContext = new GuardContext();
        replacementContext.setUserQuery(replacement);
        long supplementarySeed = NoiseRoutingGate
                .decideEscape("unicode.boundary", 1.0d, supplementaryContext).seed();
        long replacementSeed = NoiseRoutingGate
                .decideEscape("unicode.boundary", 1.0d, replacementContext).seed();

        assertAll(
                () -> assertEquals(prefix, safeSupplementary),
                () -> assertNotEquals(replacementSeed, supplementarySeed));
    }

    @Test
    void safePrefixKeepsBmpAndCompletePairControls() throws Exception {
        assertEquals("y".repeat(200), invokeSafe("y".repeat(201)));
        String completePair = "q".repeat(198) + "\uD83D\uDE00";
        assertEquals(completePair, invokeSafe(completePair + "tail"));
    }

    private static String invokeSafe(String value) throws Exception {
        Method method = NoiseRoutingGate.class.getDeclaredMethod("safe", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }
}
