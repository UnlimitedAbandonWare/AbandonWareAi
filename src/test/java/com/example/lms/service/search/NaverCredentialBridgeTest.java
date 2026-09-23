package com.example.lms.service.search;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NaverCredentialBridgeTest {

    @Test
    void explicitKeysWinOverClientPair() {
        assertEquals(
                "primary-id:primary-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "primary-id:primary-secret",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void fullLadderUsesNaverKeysEnvBeforeClientPair() throws Exception {
        Method method = NaverCredentialBridge.class.getMethod(
                "resolveKeysCsvFull", String.class, String.class, String.class, String.class);

        assertEquals(
                "env-id:env-secret",
                method.invoke(null,
                        "${naver.keys:${NAVER_KEYS:}}",
                        "env-id:env-secret",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void clientPairBridgeIsUsedOnlyWhenBothSidesArePresent() {
        assertEquals(
                "fallback-id:fallback-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "",
                        " fallback-id ",
                        " fallback-secret "));
    }

    @Test
    void unresolvedExplicitKeysPlaceholderFallsBackToClientPair() {
        assertEquals(
                "fallback-id:fallback-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "${NAVER_KEYS}",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void missingOrPlaceholderPairDoesNotBridge() {
        assertEquals("", NaverCredentialBridge.resolveKeysCsv("", "test", "fallback-secret"));
        assertEquals("", NaverCredentialBridge.resolveKeysCsv("", "fallback-id", "${NAVER_CLIENT_SECRET}"));
    }

    @Test
    void malformedExplicitKeysFallBackToClientPair() {
        assertEquals(
                "fallback-id:fallback-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "primary-id:",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void bareClientPairCsvIsAcceptedAsExplicitKeys() {
        assertEquals(
                "primary-id,primary-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "primary-id,primary-secret",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void semicolonAndQuotedColonFormsAreAcceptedAsExplicitKeys() {
        assertEquals(
                "primary-id;primary-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "primary-id;primary-secret",
                        "fallback-id",
                        "fallback-secret"));
        assertEquals(
                "\"primary-id:primary-secret\"",
                NaverCredentialBridge.resolveKeysCsv(
                        "\"primary-id:primary-secret\"",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void quotedCommaFormIsAcceptedAsExplicitKeys() {
        assertEquals(
                "\"primary-id,primary-secret\"",
                NaverCredentialBridge.resolveKeysCsv(
                        "\"primary-id,primary-secret\"",
                        "fallback-id",
                        "fallback-secret"));
    }

    @Test
    void dummyExplicitKeysFallBackToClientPair() {
        assertEquals(
                "fallback-id:fallback-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "test:test",
                        "fallback-id",
                        "fallback-secret"));
        assertEquals(
                "fallback-id:fallback-secret",
                NaverCredentialBridge.resolveKeysCsv(
                        "sk-local:changeme",
                        "fallback-id",
                        "fallback-secret"));
    }
}
