package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePatternJsonlWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void jsonlWriterStoresHashOnlyMessageDiagnostics() throws Exception {
        ObjectMapper om = new ObjectMapper();
        NovaFailurePatternProperties props = new NovaFailurePatternProperties();
        Path out = tempDir.resolve("failure-pattern.jsonl");
        props.getJsonl().setPath(out.toString());
        props.getJsonl().setWriteEnabled(true);

        FailurePatternJsonlWriter writer = new FailurePatternJsonlWriter(om, props);
        String raw = "Circuit breaker OPEN ownerToken=owner-secret api_key=sk-secret123456 raw query=private text";

        writer.write(new FailurePatternEvent(
                123L,
                FailurePatternKind.CIRCUIT_OPEN,
                "llm",
                "generic",
                1000L,
                "default",
                "test.logger",
                "WARN",
                raw
        ));

        String line = Files.readString(out);
        JsonNode node = om.readTree(line);
        String message = node.path("message").asText();

        assertEquals("llm", node.path("source").asText());
        assertEquals("WARN", node.path("level").asText());
        assertTrue(message.contains("present=true"));
        assertTrue(message.contains("hash12="));
        assertFalse(line.contains("owner-secret"));
        assertFalse(line.contains("sk-secret123456"));
        assertFalse(line.contains("private text"));
        assertFalse(line.contains("raw query"));
    }

    @Test
    void jsonlWriterFallbackCatchUsesRedactedSuppressionBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternJsonlWriter.java"));
        String helper = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternTrace.java"));

        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePatternJsonl.write\", ignored);"));
        assertTrue(helper.contains("TraceStore.put(\"failpattern.suppressed.\" + safeStage, true);"));
        assertTrue(helper.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertFalse(helper.contains("failure.getMessage()"));
        assertFalse(helper.contains("failure.toString()"));
    }
}
