package com.example.lms.uaw.autolearn;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class UawDatasetTrainingDataFilterTest {

    @Test
    void invalidConfiguredRegexIsSkippedButValidPatternStillApplies(CapturedOutput output) {
        UawDatasetFilterProperties props = new UawDatasetFilterProperties();
        UawDatasetFilterProperties.Rule rule = new UawDatasetFilterProperties.Rule();
        rule.setName("invalid_plus_valid");
        rule.setAction(UawDatasetFilterProperties.Action.EXCLUDE);
        rule.setScope(UawDatasetFilterProperties.Scope.ANSWER);
        rule.setGroup("banner");
        rule.setHard(true);
        rule.setPriority(100);
        rule.setPatterns(List.of("ownerToken-secret[", "valid-marker"));
        props.setRules(List.of(rule));

        UawDatasetTrainingDataFilter filter = new UawDatasetTrainingDataFilter(props, null);
        UawDatasetTrainingDataFilter.FilterDecision decision =
                filter.filter("question", "answer contains valid-marker", "model");

        assertFalse(decision.accept());
        assertEquals(UawDatasetTrainingDataFilter.DecisionType.EXCLUDE_HARD, decision.decisionType());
        assertEquals("invalid_plus_valid", decision.decisiveRule().name());

        String logs = output.getAll();
        assertFalse(logs.contains("ownerToken-secret"));
        assertFalse(logs.contains("ownerToken-secret["));
        assertTrue(logs.contains("[AWX][yaml]"));
        assertTrue(logs.contains("patternHash="));
        assertTrue(logs.contains("patternLength=18"));
        assertTrue(logs.contains("PatternSyntaxException"));
    }

    @Test
    void decisionMetricTagsDoNotExposeRawSensitiveRuleLabels() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawRule = "ownertoken " + fakeSecret;
        String rawGroup = "private prompt " + fakeSecret;
        UawDatasetFilterProperties props = new UawDatasetFilterProperties();
        UawDatasetFilterProperties.Rule rule = new UawDatasetFilterProperties.Rule();
        rule.setName(rawRule);
        rule.setAction(UawDatasetFilterProperties.Action.EXCLUDE);
        rule.setScope(UawDatasetFilterProperties.Scope.ANSWER);
        rule.setGroup(rawGroup);
        rule.setHard(true);
        rule.setPriority(100);
        rule.setPatterns(List.of("valid-marker"));
        props.setRules(List.of(rule));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        UawDatasetTrainingDataFilter filter = new UawDatasetTrainingDataFilter(props, provider(registry));
        filter.filter("question", "answer contains valid-marker", "model");

        Meter meter = registry.getMeters().stream()
                .filter(m -> "uaw.autolearn.dataset_filter.decisions".equals(m.getId().getName()))
                .findFirst()
                .orElseThrow();
        Map<String, String> tags = meter.getId().getTags().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
        String rendered = tags.toString();

        assertFalse(rendered.contains(fakeSecret), rendered);
        assertFalse(rendered.toLowerCase().contains("ownertoken"), rendered);
        assertFalse(rendered.contains("private prompt"), rendered);
        assertTrue(tags.get("rule").startsWith("hash_"), rendered);
        assertTrue(tags.get("group").startsWith("hash_"), rendered);
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyDegradedBannerMarkerIsDetected() {
        UawDatasetTrainingDataFilter filter =
                new UawDatasetTrainingDataFilter(new UawDatasetFilterProperties(), null);

        assertTrue(filter.shouldExcludeDeprecated("question", "※ [DEGRADED MODE] fallback answer", "model"));
    }
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
