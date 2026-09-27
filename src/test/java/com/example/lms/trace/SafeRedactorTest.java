package com.example.lms.trace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeRedactorTest {

    @Test
    void restrictedTextBecomesHashLenSummary() {
        String raw = "private user query <script>alert(1)</script>";

        Object value = SafeRedactor.diagnosticValue("rawQuery", raw);

        Map<?, ?> summary = assertInstanceOf(Map.class, value);
        assertEquals(Boolean.TRUE, summary.get("present"));
        assertEquals(raw.length(), summary.get("len"));
        assertEquals(SafeRedactor.hash12(raw), summary.get("hash12"));
        assertFalse(String.valueOf(summary).contains(raw));
        assertFalse(String.valueOf(summary).contains("<script>"));
    }

    @Test
    void identifierBecomesStableHashValue() {
        assertEquals(SafeRedactor.hashValue("session-raw-123"),
                SafeRedactor.diagnosticValue("sessionId", "session-raw-123"));
        assertEquals(SafeRedactor.hashValue("C:\\secret\\train_rag.jsonl"),
                SafeRedactor.diagnosticValue("datasetPath", "C:\\secret\\train_rag.jsonl"));
    }

    @Test
    void alreadyHashedOpaquePacketReferencesRemainUsableAcrossToolCalls() {
        String packetRef = "hash:0123456789ab";

        assertEquals(packetRef, SafeRedactor.diagnosticValue("packetRef", packetRef));
        assertEquals(
                Map.of("packetRef", packetRef, "queryTraceRefs", java.util.List.of(packetRef)),
                SafeRedactor.diagnosticValue("toolResponse", Map.of(
                        "packetRef", packetRef,
                        "queryTraceRefs", java.util.List.of(packetRef))));
    }

    @Test
    void pathLikeKeysBecomeHashLenSummary() {
        String raw = "/api/private/" + com.example.lms.test.SecretFixtures.openAiKey() + "?ownerToken=secret";

        Object value = SafeRedactor.diagnosticValue("http.path", raw);

        Map<?, ?> summary = assertInstanceOf(Map.class, value);
        assertEquals(Boolean.TRUE, summary.get("present"));
        assertEquals(raw.length(), summary.get("len"));
        assertEquals(SafeRedactor.hash12(raw), summary.get("hash12"));
        assertFalse(String.valueOf(summary).contains("/api/private"));
        assertFalse(String.valueOf(summary).contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(String.valueOf(summary).contains("ownerToken"));
    }

    @Test
    void compressorOriginalTextMetadataBecomesSummary() {
        String raw = "raw original compressed text";

        Object value = SafeRedactor.diagnosticValue("_nova.origText", raw);

        Map<?, ?> summary = assertInstanceOf(Map.class, value);
        assertEquals(Boolean.TRUE, summary.get("present"));
        assertEquals(SafeRedactor.hash12(raw), summary.get("hash12"));
        assertFalse(String.valueOf(summary).contains(raw));
    }

    @Test
    void allowlistedScalarStaysReadable() {
        assertEquals("provider-disabled", SafeRedactor.diagnosticValue("disabledReason", "provider-disabled"));
        assertEquals(3, SafeRedactor.diagnosticValue("returnedCount", 3));
        assertTrue(String.valueOf(SafeRedactor.diagnosticValue("url", "https://example.com/a?q=secret"))
                .contains("example.com"));
    }

    @Test
    void canonicalHarmonyAuthorityLabelsRemainReadableAndSecretMasked() {
        assertEquals("MoE", SafeRedactor.diagnosticValue("retrievalOrder.lastSetBy", "MoE"));
        assertEquals("OVERDRIVE",
                SafeRedactor.diagnosticValue("routing.executionPlan.primaryMode", "OVERDRIVE"));
        assertEquals("CfvmKallocLearningProperties",
                SafeRedactor.diagnosticValue("cfvm.tempSource", "CfvmKallocLearningProperties"));
        assertEquals("ollama",
                SafeRedactor.diagnosticValue("hypernova.whitening.provider", "ollama"));

        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Object sanitized = SafeRedactor.diagnosticValue(
                "cfvm.tempSource",
                "CfvmKallocLearningProperties " + secret);
        assertFalse(String.valueOf(sanitized).contains(secret));

        String providerSecret = "sk-" + "123456789012345678901234";
        Object providerSanitized = SafeRedactor.diagnosticValue(
                "hypernova.whitening.provider",
                providerSecret);
        assertFalse(String.valueOf(providerSanitized).contains(providerSecret));
    }

    @Test
    void canonicalHarmonyAuthorityLabelsHashUnexpectedFreeFormValues() {
        String raw = "private student question with arbitrary owner text";
        for (String key : java.util.List.of(
                "retrievalOrder.lastSetBy",
                "routing.executionPlan.primaryMode",
                "cfvm.tempSource",
                "hypernova.whitening.provider")) {
            Object value = SafeRedactor.diagnosticValue(key, raw);
            Map<?, ?> summary = assertInstanceOf(Map.class, value, key);
            assertEquals(Boolean.TRUE, summary.get("present"), key);
            assertEquals(SafeRedactor.hash12(raw), summary.get("hash12"), key);
            assertFalse(String.valueOf(summary).contains(raw), key);
        }
    }

    @Test
    void dottedApiKeyPresenceFieldsAreRedacted() {
        assertEquals("(redacted)", SafeRedactor.diagnosticValue("env.gemini.api.key.present", true));
        assertEquals("(redacted)", SafeRedactor.diagnosticValue("env.supabase.service_role.key.present", true));
    }

    @Test
    void reasonLikeDiagnosticsKeepCodesButHashFreeFormText() {
        String raw = "provider disabled for private query ownerToken=secret";

        assertEquals("provider-disabled", SafeRedactor.diagnosticValue("disabledReason", "provider-disabled"));
        Object value = SafeRedactor.diagnosticValue("disabledReason", raw);

        assertTrue(String.valueOf(value).startsWith("hash:"), String.valueOf(value));
        assertFalse(String.valueOf(value).contains("private query"), String.valueOf(value));
        assertFalse(String.valueOf(value).contains("ownerToken"), String.valueOf(value));
    }

    @Test
    void traceFlagDetailKeepsReasonCodesWithoutRawText() {
        assertEquals("missing_api_key", SafeRedactor.traceFlagDetail("missing_api_key"));
        assertEquals("quota_exhausted", SafeRedactor.traceFlagDetail("quota_exhausted"));
        assertEquals("0", SafeRedactor.traceFlagDetail(0));
        assertEquals("present", SafeRedactor.traceFlagDetail("Private reason text with spaces"));
        assertEquals("present", SafeRedactor.traceFlagDetail("api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
    }

    @Test
    void traceLabelKeepsCodesAndHashesRawText() {
        String raw = "Private Engine ownerToken=raw-secret";

        assertEquals("Naver", SafeRedactor.traceLabel("Naver"));
        assertEquals("await_timeout", SafeRedactor.traceLabel("await_timeout"));
        assertEquals("fallback", SafeRedactor.traceLabelOrFallback("", "fallback"));
        assertTrue(SafeRedactor.traceLabel(raw).startsWith("hash:"));
        assertFalse(SafeRedactor.traceLabel(raw).contains(raw));
    }

    @Test
    void traceLabelHashesSecretShapedFieldLabels() {
        assertTrue(SafeRedactor.traceLabel("apiKey").startsWith("hash:"));
        assertTrue(SafeRedactor.traceLabel("web.naver.apiKey").startsWith("hash:"));
        assertTrue(SafeRedactor.traceLabel("serviceRoleKey").startsWith("hash:"));
        assertTrue(SafeRedactor.traceLabel("ownerToken").startsWith("hash:"));
        assertEquals("tokenBucket", SafeRedactor.traceLabel("tokenBucket"));
        assertEquals("keySource", SafeRedactor.traceLabel("keySource"));
    }

    @Test
    void supabaseApiKeysAreMaskedAndNeverUsedAsTraceLabels() {
        String secretKey = "sb_secret_" + "1234567890abcdef";
        String publishableKey = "sb_publishable_" + "1234567890abcdef";

        assertFalse(SafeRedactor.redact("key=" + secretKey).contains(secretKey));
        assertFalse(SafeRedactor.redact("key=" + publishableKey).contains(publishableKey));
        assertEquals("present", SafeRedactor.traceFlagDetail(secretKey));
        assertTrue(SafeRedactor.traceLabel(secretKey).startsWith("hash:"));
        assertFalse(SafeRedactor.traceLabel(secretKey).contains(secretKey));
    }
}
