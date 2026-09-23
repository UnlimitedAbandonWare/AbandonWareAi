package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MlaOtelBridgeTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("otel.mla.bridge.enabled");
        MlaOtelBridge.configure(false);
        TraceStore.clear();
    }

    @Test
    void bridgeIsNoopByDefault() {
        System.clearProperty("otel.mla.bridge.enabled");
        MlaOtelBridge.configure(false);

        assertThat(MlaOtelBridge.enabled()).isFalse();
    }

    @Test
    void attributesWhitelistHashesIdsAndDropsRawPayloads() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", "MLA-05");
        event.put("stage", "ingest");
        event.put("step", "complete");
        event.put("component", "ConversationArchiveIngestService");
        event.put("status", "ok");
        event.put("sessionId", "session-raw");
        event.put("requestId", "request-raw");
        event.put("traceId", "trace-raw");
        event.put("query", "raw query text");
        event.put("snippet", "raw snippet text");
        event.put("output", Map.of("returnedCount", 3, "selectedCount", 2, "stageMs", 7));
        event.put("control", Map.of(
                "anchorHash", "abc123",
                "matrixTile", 4,
                "ablationDrop", 0.42d,
                "routeHint", "brave_mode"));

        Map<String, Object> attrs = MlaOtelBridge.attributes(event);

        assertThat(attrs).containsEntry("mla.phase", "MLA-05");
        assertThat(attrs).containsEntry("gen_ai.operation.name", "ingest");
        assertThat(attrs).containsEntry("rag.retriever.kind", "conversation_archive");
        assertThat(attrs.get("session.id.hash")).asString().startsWith("hash:");
        assertThat(attrs.get("request.id.hash")).asString().startsWith("hash:");
        assertThat(attrs.get("trace.id.hash")).asString().startsWith("hash:");
        assertThat(attrs.get("mla.anchor.hash")).asString().startsWith("hash:");
        assertThat(attrs).containsEntry("rag.matrix.tile", 4L);
        assertThat(((Number) attrs.get("rag.ablation.drop")).doubleValue()).isEqualTo(0.42d);
        assertThat(attrs).containsEntry("rag.route.hint", "brave_mode");
        assertThat(attrs.toString())
                .doesNotContain("session-raw", "request-raw", "trace-raw", "raw query text", "raw snippet text");
    }

    @Test
    void attributesPreserveFractionalSourceDiversity() {
        Map<String, Object> event = Map.of(
                "output", Map.of(
                        "returnedCount", 2,
                        "sourceDiversity", 0.5d));

        Map<String, Object> attrs = MlaOtelBridge.attributes(event);

        assertThat(attrs).containsEntry("rag.returned_count", 2L);
        assertThat(attrs.get("rag.source_diversity")).isInstanceOf(Double.class);
        assertThat(((Number) attrs.get("rag.source_diversity")).doubleValue()).isEqualTo(0.5d);
    }

    @Test
    void attributesDoNotExposeRawSensitiveLabels() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawLabel = "ownertoken " + fakeSecret;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", "MLA-05 " + rawLabel);
        event.put("stage", "ingest " + rawLabel);
        event.put("step", "complete " + rawLabel);
        event.put("component", "ConversationArchiveIngestService " + rawLabel);
        event.put("status", "ok");

        Map<String, Object> attrs = MlaOtelBridge.attributes(event);

        assertThat(attrs.toString())
                .doesNotContain(fakeSecret, "ownertoken")
                .contains("hash_");
    }

    @Test
    void invalidNumericAttributesUseStableReasonCodeWithoutRawValue() {
        Map<String, Object> longEvent = new LinkedHashMap<>();
        longEvent.put("output", Map.of("returnedCount", "ownerToken-not-a-long"));

        Map<String, Object> longAttrs = MlaOtelBridge.attributes(longEvent);

        assertThat(longAttrs).doesNotContainKey("rag.returned_count");
        assertThat(TraceStore.get("mla.otel.suppressed.errorType")).isEqualTo("invalid_number");
        assertThat(String.valueOf(TraceStore.getAll()))
                .doesNotContain("ownerToken-not-a-long", "NumberFormatException");

        TraceStore.clear();
        Map<String, Object> doubleEvent = new LinkedHashMap<>();
        doubleEvent.put("control", Map.of("ablationDrop", "ownerToken-not-a-double"));

        Map<String, Object> doubleAttrs = MlaOtelBridge.attributes(doubleEvent);

        assertThat(doubleAttrs).doesNotContainKey("rag.ablation.drop");
        assertThat(TraceStore.get("mla.otel.suppressed.errorType")).isEqualTo("invalid_number");
        assertThat(String.valueOf(TraceStore.getAll()))
                .doesNotContain("ownerToken-not-a-double", "NumberFormatException");

        TraceStore.clear();
        Map<String, Object> nonFiniteLongEvent = new LinkedHashMap<>();
        nonFiniteLongEvent.put("output", Map.of("returnedCount", Double.POSITIVE_INFINITY));

        Map<String, Object> nonFiniteLongAttrs = MlaOtelBridge.attributes(nonFiniteLongEvent);

        assertThat(nonFiniteLongAttrs).doesNotContainKey("rag.returned_count");
        assertThat(TraceStore.get("mla.otel.suppressed.errorType")).isEqualTo("invalid_number");

        TraceStore.clear();
        Map<String, Object> nonFiniteDoubleEvent = new LinkedHashMap<>();
        nonFiniteDoubleEvent.put("control", Map.of("ablationDrop", Double.NaN));

        Map<String, Object> nonFiniteDoubleAttrs = MlaOtelBridge.attributes(nonFiniteDoubleEvent);

        assertThat(nonFiniteDoubleAttrs).doesNotContainKey("rag.ablation.drop");
        assertThat(TraceStore.get("mla.otel.suppressed.errorType")).isEqualTo("invalid_number");
    }

    @Test
    void overflowStringDoubleAttributeIsSuppressedWithoutRawValue() {
        Map<String, Object> event = Map.of(
                "control", Map.of("ablationDrop", "1.0e309"));

        Map<String, Object> attrs = MlaOtelBridge.attributes(event);

        assertThat(attrs).doesNotContainKey("rag.ablation.drop");
        assertThat(TraceStore.get("mla.otel.suppressed.count")).isEqualTo(1L);
        assertThat(TraceStore.get("mla.otel.suppressed.stage")).isEqualTo("doubleValue");
        assertThat(TraceStore.get("mla.otel.suppressed.errorType")).isEqualTo("invalid_number");
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain("1.0e309");
    }

    @Test
    void appendEventStoresSanitizedConversationArchiveEventWithoutRawIds() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "rag.ingest");
        event.put("phase", "MLA-05");
        event.put("stage", "ingest");
        event.put("step", "complete");
        event.put("component", "ConversationArchiveIngestService");
        event.put("status", "ok");
        event.put("sessionIdHash", "hash:abc123");
        event.put("output", Map.of("returnedCount", 1));

        OrchTrace.appendEvent(event);

        assertThat(String.valueOf(TraceStore.get(OrchTrace.TRACE_KEY_EVENTS_V1)))
                .contains("rag.ingest", "hash:abc123")
                .doesNotContain("session-raw", "raw query", "raw snippet");
    }

    @Test
    void numericAttributeHelpersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/trace/MlaOtelBridge.java"))
                .replace("\r\n", "\n");

        assertThat(source).doesNotContain("catch (Exception ignore) {\n            return null;\n        }");
        assertThat(source).doesNotContain("catch (NumberFormatException ignore) {\n            return null;\n        }");
        assertThat(source).contains("catch (NumberFormatException e) {\n            traceSuppressed(\"longValue\", e);");
        assertThat(source).contains("catch (NumberFormatException e) {\n            traceSuppressed(\"doubleValue\", e);");
    }
}
