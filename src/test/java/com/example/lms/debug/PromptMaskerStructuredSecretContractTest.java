package com.example.lms.debug;

import com.example.lms.conversation.archive.ConversationMessageRecord;
import com.example.lms.conversation.archive.ConversationNoiseClassifier;
import com.example.lms.conversation.archive.ConversationTopicTimelineBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptMaskerStructuredSecretContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String secret = "fixture" + "SecretValue";

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @Test
    void masksQuotedJsonCredentialKeysWithoutBreakingTheDocument() throws Exception {
        for (String key : List.of("api_key", "token", "secret", "password", "client_secret", "ownerToken")) {
            String input = mapper.writeValueAsString(Map.of(key, secret, "state", "ready"));
            String output = PromptMasker.mask(input);
            assertFalse(output.contains(secret), "quoted_json_value_exposed");
            var tree = mapper.readTree(output);
            assertTrue(tree.get(key).asText().equals("*".repeat(secret.length())), "quoted_json_mask_shape");
            assertTrue(tree.get("state").asText().equals("ready"), "unrelated_json_value_changed");
            assertTrue(output.length() == input.length(), "quoted_json_length_changed");
        }
    }

    @Test
    void masksSpacesAndEscapesInsideQuotedStringValues() throws Exception {
        String raw = "fixture first \"second\" \\ third";
        String input = mapper.writeValueAsString(Map.of("password", raw, "state", "ready"));
        String output = PromptMasker.mask(input);
        assertFalse(output.contains("fixture") || output.contains("second") || output.contains("third"), "quoted_value_suffix_exposed");
        assertTrue(mapper.readTree(output).get("password").asText().matches("\\*+"), "escaped_json_mask_shape");
        assertTrue(output.length() == input.length(), "escaped_json_length_changed");
    }

    @Test
    void masksQuotedValuesForUnquotedKeysAndPreservesSeparators() {
        String raw = "fixture spaced value";
        String input = "before password = \"" + raw + "\" after";
        String output = PromptMasker.mask(input);
        assertTrue(output.equals("before password = \"" + "*".repeat(raw.length()) + "\" after"), "assignment_value_or_separator_contract");
    }

    @Test
    void longQuotedValuesDoNotOverflowTheRegexStack() {
        String value = "f".repeat(20_000);
        String output = PromptMasker.mask("{\"api_key\":\"" + value + "\"}");
        assertFalse(output.contains("ffff"), "long_quoted_secret_exposed");
        assertTrue(output.equals("{\"api_key\":\"" + "*".repeat(value.length()) + "\"}"), "long_quoted_shape_changed");
        assertTrue(PromptMasker.mask("password=\"" + value) != null, "malformed_input_failsoft_changed");
    }

    @Test
    void supportsSingleQuotedKeysAndVendorFieldNames() {
        for (String key : List.of("ownerToken", "groq.api-key", "pineconeApiKey", "x-naver-client-secret")) {
            String input = "'" + key + "': '" + secret + "'";
            String output = PromptMasker.mask(input);
            assertTrue(output.equals("'" + key + "': '" + "*".repeat(secret.length()) + "'"), "single_quote_or_vendor_field_contract");
        }
    }

    @Test
    void preservesNonSecretKeysEmptyValuesAndOrdinaryText() {
        for (String input : List.of("{\"token_count\":\"ready\"}", "{\"password_policy\":\"strict\"}",
                "{\"client_secret_name\":\"sample\"}", "{\"api_key\":\"\"}", "ordinary conversation")) {
            assertTrue(PromptMasker.mask(input).equals(input), "nonsecret_control_changed");
        }
        assertTrue(PromptMasker.mask(null) == null, "null_contract_changed");
    }

    @Test
    void preservesPlainAssignmentsAndMaskingIdempotence() {
        String mixed = "api_key=" + secret + " {\"ownerToken\":\"" + secret + "\"}";
        String output = PromptMasker.mask(mixed);
        assertFalse(output.contains(secret), "mixed_secret_exposed");
        assertTrue(output.startsWith("api_key=" + "*".repeat(secret.length())), "plain_assignment_contract_changed");
        assertTrue(PromptMasker.mask(output).equals(output), "masking_not_idempotent");
    }

    @Test
    void safeRedactorUsesTheSameStructuredCredentialBoundary() {
        String output = SafeRedactor.redact("{\"api_key\":\"" + secret + "\"}");
        assertFalse(output.contains(secret), "safe_redactor_structured_secret_exposed");
        assertTrue(output.contains("*".repeat(secret.length())), "safe_redactor_mask_missing");
    }

    @Test
    void archiveClassifierAndChunkBuilderDoNotPersistQuotedSecrets() {
        assertArchiveMasks("Please review {\"api_key\":\"" + secret + "\"} for this conversation", secret);
    }

    @Test
    void archiveCompositionAlreadyMasksKoreanAdjacentPhones() {
        String phone = "010-" + "1234-" + "5678";
        assertArchiveMasks("연락처는 전화" + phone + "입니다", phone);
    }

    private void assertArchiveMasks(String input, String hidden) {
        var decision = new ConversationNoiseClassifier().classifyText(input);
        assertTrue(decision.ingestible(), "fixture_not_admissible");
        var record = new ConversationMessageRecord("synthetic.txt", 1, "", "fixture", input);
        var chunks = new ConversationTopicTimelineBuilder().build("fixture-session",
                List.of(new ConversationTopicTimelineBuilder.ClassifiedRecord(record, decision)), 1000, 4);
        assertTrue(chunks.size() == 1, "unexpected_chunk_count");
        assertFalse(chunks.get(0).text().contains(hidden), "archive_chunk_secret_exposed");
        assertFalse(chunks.get(0).metadata().toString().contains(hidden), "archive_metadata_secret_exposed");
        var trace = TraceStore.getAll();
        assertTrue(trace.keySet().equals(Set.of("guard.pii.enabled", "guard.pii.mode", "guard.pii.changed",
                "guard.pii.inputLength", "guard.pii.outputLength")), "unexpected_trace_keys");
        assertFalse(trace.toString().contains(hidden), "trace_secret_exposed");
        assertTrue(trace.get("guard.pii.inputLength") instanceof Number
                && trace.get("guard.pii.outputLength") instanceof Number, "length_metrics_missing");
    }
}
