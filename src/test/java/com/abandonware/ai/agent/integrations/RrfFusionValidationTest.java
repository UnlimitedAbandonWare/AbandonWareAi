package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionValidationTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void scalarParserRejectsNonPositiveKAndNonFiniteOrNegativeWeights() {
        assertThat(RrfFusion.parseEnvDouble("RRF_K", 60.0d, "0")).isEqualTo(60.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_K", 60.0d, "-1")).isEqualTo(60.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_K", 60.0d, "NaN")).isEqualTo(60.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_K", 60.0d, "Infinity")).isEqualTo(60.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_W_LOCAL", 1.0d, "-1")).isEqualTo(1.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_W_LOCAL", 1.0d, "NaN")).isEqualTo(1.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_W_WEB", 1.0d, "Infinity")).isEqualTo(1.0d);
        assertThat(RrfFusion.parseEnvDouble("RRF_W_WEB", 1.0d, "0")).isZero();
    }

    @Test
    void invalidTupleFallsBackAtomicallyAndAllScoresRemainFinite() throws Exception {
        List<String[]> invalidTuples = List.of(
                new String[]{"0", "2", "9", "invalid_k"},
                new String[]{"-1", "2", "9", "invalid_k"},
                new String[]{"NaN", "2", "9", "invalid_k"},
                new String[]{"Infinity", "2", "9", "invalid_k"},
                new String[]{"60", "NaN", "9", "non_finite_weight"},
                new String[]{"60", "Infinity", "9", "non_finite_weight"},
                new String[]{"60", "-1", "9", "negative_weight"},
                new String[]{"60", "0", "0", "zero_weight_sum"},
                new String[]{"60", "private-invalid-weight-4711", "9", "non_finite_weight"});

        for (String[] tuple : invalidTuples) {
            TraceStore.clear();
            List<Map<String, Object>> result = fuseWithRawConfig(tuple[0], tuple[1], tuple[2]);

            assertThat(result).extracting(row -> (double) row.get("rrfScore"))
                    .allSatisfy(score -> {
                        assertThat(score).isFinite();
                        assertThat(score).isEqualTo(1.0d / 61.0d);
                    });
            assertThat(TraceStore.get("agent.rrf.config.fallback")).isEqualTo(Boolean.TRUE);
            assertThat(TraceStore.get("agent.rrf.config.fallback.count")).isEqualTo(1);
            assertThat(TraceStore.get("agent.rrf.config.fallback.reason")).isEqualTo(tuple[3]);
            assertThat(String.valueOf(TraceStore.getAll()))
                    .doesNotContain("private-invalid-weight-4711");
        }
    }

    @Test
    void validCustomTupleKeepsTheExistingWeightedRrfFormula() throws Exception {
        List<Map<String, Object>> result = fuseWithRawConfig("1", "3", "1");

        assertThat(result).extracting(row -> row.get("id"), row -> row.get("rrfScore"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("local-z", 1.5d),
                        org.assertj.core.groups.Tuple.tuple("web-a", 0.5d));
        assertThat(TraceStore.get("agent.rrf.config.fallback")).isNull();
    }

    @Test
    void equalScoresPreserveFirstSeenOrderDeterministically() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(fuseWithRawConfig("60", "1", "1"))
                    .extracting(row -> row.get("id"))
                    .containsExactly("local-z", "web-a");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fuseWithRawConfig(
            String rawK,
            String rawLocalWeight,
            String rawWebWeight) throws Exception {
        Method method = RrfFusion.class.getDeclaredMethod(
                "fuse",
                List.class,
                List.class,
                String.class,
                String.class,
                String.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(
                null,
                List.of(Map.of("id", "local-z")),
                List.of(Map.of("id", "web-a")),
                rawK,
                rawLocalWeight,
                rawWebWeight);
    }
}
