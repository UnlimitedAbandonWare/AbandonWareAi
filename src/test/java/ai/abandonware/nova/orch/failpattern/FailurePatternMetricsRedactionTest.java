package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePatternMetricsRedactionTest {

    @Test
    void metricsTagsDoNotExposeRawSensitiveSourceOrDetail() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawSource = "ownertoken " + fakeSecret;
        String rawDetail = "private prompt " + fakeSecret;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FailurePatternMetrics metrics = new FailurePatternMetrics(registry, new NovaFailurePatternProperties());

        metrics.increment(new FailurePatternMatch(
                FailurePatternKind.SEARCH_ZERO_RESULT,
                rawSource,
                rawDetail));

        Meter meter = registry.getMeters().stream()
                .filter(m -> "nova.failure.pattern".equals(m.getId().getName()))
                .findFirst()
                .orElseThrow();
        Map<String, String> tags = meter.getId().getTags().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
        String rendered = tags.toString();

        assertEquals("search_zero_result", tags.get("kind"));
        assertFalse(rendered.contains(fakeSecret), rendered);
        assertFalse(rendered.toLowerCase().contains("ownertoken"), rendered);
        assertFalse(rendered.contains("private prompt"), rendered);
        assertTrue(tags.get("source").startsWith("hash_"), rendered);
        assertTrue(tags.get("detail").startsWith("hash_"), rendered);
    }
}
