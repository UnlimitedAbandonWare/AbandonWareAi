package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHarmonyConditionalHoldShapeContractTest {

    private static final Pattern VERSION_FACT = Pattern.compile("\\bv?\\d+\\.\\d+(?:\\.\\d+)?\\b");

    @AfterEach
    void clearTrace() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void evidenceLimitedAnswerKeepsExplicitConditionalHoldDirective() {
        Map<String, Object> meta = new LinkedHashMap<>();
        String userQuery = "Check the latest Spring Boot release using an official source and answer in one sentence. "
                + "If official evidence is insufficient, explicitly answer HOLD.";
        GuardContext guardContext = new GuardContext();
        guardContext.setUserQuery(userQuery);
        GuardContextHolder.set(guardContext);

        String composerAnswer = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                new EvidenceAwareGuard.EvidenceDoc(
                        "https://community.example.invalid/spring-boot",
                        "Community summary",
                        "An unverified community post guesses a recent Spring Boot version.")),
                false);

        boolean composerHasConditionalHold = composerAnswer.contains("HOLD");
        assertFalse(composerHasConditionalHold,
                "the upstream evidence-list boundary currently omits the conditional HOLD token");

        String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                meta,
                userQuery,
                composerAnswer);

        BoundaryPacket packet = new BoundaryPacket(
                shortHash(userQuery),
                shortHash(composerAnswer),
                shortHash(shaped),
                composerHasConditionalHold,
                shaped.contains("HOLD"),
                VERSION_FACT.matcher(composerAnswer).find(),
                VERSION_FACT.matcher(shaped).find(),
                sentenceCount(composerAnswer),
                sentenceCount(shaped));

        assertTrue(shaped.contains("HOLD"),
                "an evidence-limited answer must preserve the user's explicit conditional HOLD directive; "
                        + packet);
    }

    @Test
    void quotedTranslatedNegatedAndMetalinguisticHoldTextDoesNotBecomeACommand() {
        for (String userQuery : List.of(
                "Explain in one sentence the rule \"if official evidence is insufficient, explicitly answer HOLD.\"",
                "Translate \"if official evidence is insufficient, answer HOLD\" into Korean in one sentence.",
                "If official evidence is insufficient, do not answer HOLD; return the best available summary in one sentence.",
                "Discuss in one sentence why someone might say: if official evidence is insufficient, answer HOLD.")) {
            GuardContext guardContext = new GuardContext();
            guardContext.setUserQuery(userQuery);
            GuardContextHolder.set(guardContext);
            String composerAnswer = new EvidenceAwareGuard().degradeToEvidenceList(List.of(
                    new EvidenceAwareGuard.EvidenceDoc(
                            "https://community.example.invalid/spring-boot",
                            "Community summary",
                            "An unverified community post guesses a recent Spring Boot version.")),
                    false);

            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    new LinkedHashMap<>(), userQuery, composerAnswer);

            assertFalse("HOLD".equals(shaped), userQuery);
            GuardContextHolder.clear();
            TraceStore.clear();
        }

        String directInstruction = "Check the latest Spring Boot release using an official source. "
                + "If official evidence is insufficient, explicitly answer HOLD.";
        for (String nonCanonicalFallback : List.of(
                "evidence_needed: attachment is missing; the official changelog confirms the version.",
                "evidence_needed: official evidence is not missing; another source is unavailable.")) {
            String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                    new LinkedHashMap<>(), directInstruction, nonCanonicalFallback);

            assertFalse("HOLD".equals(shaped), nonCanonicalFallback);
        }
    }

    private static int sentenceCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("(?<=[.!?])\\s+", -1).length;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record BoundaryPacket(
            String requestHash,
            String composerHash,
            String shapedHash,
            boolean composerHasConditionalHold,
            boolean shapedHasConditionalHold,
            boolean composerHasVersionFact,
            boolean shapedHasVersionFact,
            int composerSentenceCount,
            int shapedSentenceCount) {
    }
}
