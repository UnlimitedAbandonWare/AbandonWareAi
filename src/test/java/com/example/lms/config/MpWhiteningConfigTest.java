package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MpWhiteningConfigTest {

    @Test
    void whiteningStatsBeansHaveExplicitNamesToAvoidNoopShadowingFullImplementation() throws Exception {
        String noopSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/stats/LowRankWhiteningStats.java"));
        String fullConfigSource = Files.readString(Path.of(
                "main/java/com/example/lms/config/MpWhiteningConfig.java"));

        assertTrue(noopSource.contains("@Component(\"noopLowRankWhiteningStats\")"));
        assertTrue(fullConfigSource.contains("@Bean(\"fullLowRankWhiteningStats\")"));
        assertTrue(fullConfigSource.contains("@Qualifier(\"fullLowRankWhiteningStats\") LowRankWhiteningStats stats"));
        assertTrue(fullConfigSource.contains("@Value(\"${embedding.provider:${embed.provider:unknown}}\") String embeddingProvider"));
        assertTrue(fullConfigSource.contains("new LowRankWhiteningTransform(stats, embeddingProvider)"));
    }
}
