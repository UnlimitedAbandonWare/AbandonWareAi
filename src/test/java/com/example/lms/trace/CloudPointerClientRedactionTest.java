package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPointerClientRedactionTest {

    @Test
    void outboundEventUsesRedactedLabelsAndDiagnosticValues() throws Exception {
        String rawType = "orchestration type " + com.example.lms.test.SecretFixtures.openAiKey() + "";
        String rawStage = "stage ownerToken=secret";
        String rawKey = "private kv key " + com.example.lms.test.SecretFixtures.openAiKey() + "";
        String rawQuery = "private cloud pointer query";

        Map<String, Object> event = CloudPointerClient.buildEvent(rawType, rawStage, Map.of(
                rawKey, rawQuery,
                "nested", Map.of("nested ownerToken=secret", rawQuery)));

        String json = new ObjectMapper().writeValueAsString(event);

        assertTrue(String.valueOf(event.get("type")).startsWith("hash:"));
        assertTrue(String.valueOf(event.get("stage")).startsWith("hash:"));
        assertTrue(json.contains("\"hash:"));
        assertFalse(json.contains(rawType));
        assertFalse(json.contains(rawStage));
        assertFalse(json.contains(rawKey));
        assertFalse(json.contains(rawQuery));
        assertFalse(json.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(json.contains("ownerToken"));
    }

    @Test
    void bearerTokenUsesPlaceholderGuard() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/CloudPointerClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TOKEN != null && !TOKEN.isBlank()"));
        assertTrue(source.contains("ConfigValueGuards.isMissing(TOKEN)"));
    }

    @Test
    void cloudPointerClientDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/CloudPointerClient.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "Cloud pointer best-effort paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertFalse(source.contains("catch (Exception ignore) { return def; }"));
        assertTrue(source.contains("catch (NumberFormatException ignore)"));
        assertTrue(source.contains("TraceStore.put(\"trace.cloudPointer.suppressed.parseInt\", true)"));
        assertTrue(source.contains("TraceStore.put(\"trace.cloudPointer.suppressed.stage\", \"parseInt\")"));
        assertTrue(source.contains("TraceStore.put(\"trace.cloudPointer.suppressed.errorType\", \"invalid_number\")"));
        assertTrue(source.contains("TraceStore.inc(\"trace.cloudPointer.suppressed.dispatch.count\")"));
        assertTrue(source.contains("TraceStore.inc(\"trace.cloudPointer.suppressed.httpPost.count\")"));
        assertTrue(source.contains("return def;"));
    }

    @Test
    void timeoutParseFallbackUsesStableInvalidNumberLabel() throws Exception {
        TraceStore.clear();
        Method method = CloudPointerClient.class.getDeclaredMethod("parseInt", String.class, int.class);
        method.setAccessible(true);

        Object parsed = method.invoke(null, "not-a-number", 1200);

        assertEquals(1200, parsed);
        assertEquals(Boolean.TRUE, TraceStore.get("trace.cloudPointer.suppressed.parseInt"));
        assertEquals("invalid_number", TraceStore.get("trace.cloudPointer.suppressed.parseInt.errorType"));
        assertEquals("parseInt", TraceStore.get("trace.cloudPointer.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("trace.cloudPointer.suppressed.errorType"));
    }

    @Test
    void httpPostFailureAddsCountOnlyTraceWithoutEndpointPayload() throws Exception {
        TraceStore.clear();
        Method method = CloudPointerClient.class.getDeclaredMethod("post", String.class, byte[].class);
        method.setAccessible(true);

        method.invoke(null, "http://[private-endpoint token", "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1L, TraceStore.get("trace.cloudPointer.suppressed.httpPost.count"));
        assertEquals("http_post", TraceStore.get("trace.cloudPointer.suppressed.stage"));
        assertEquals("invalid_url", TraceStore.get("trace.cloudPointer.suppressed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("private-endpoint"));
        assertFalse(TraceStore.getAll().toString().contains("token"));
    }

    @Test
    void endpointUrlFailuresUseStableInvalidUrlLabel() throws Exception {
        Method method = CloudPointerClient.class.getDeclaredMethod("errorType", Exception.class);
        method.setAccessible(true);

        Object label = method.invoke(null, new MalformedURLException("private endpoint token"));

        assertEquals("invalid_url", label);
        assertFalse(String.valueOf(label).contains("MalformedURLException"));
        assertFalse(String.valueOf(label).contains("private endpoint token"));
    }
}
