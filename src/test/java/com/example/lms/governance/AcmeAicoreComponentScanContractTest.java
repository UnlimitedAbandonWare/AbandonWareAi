package com.example.lms.governance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AcmeAicoreComponentScanContractTest {

    @Test
    void unscannedAcmeSkeletonsDoNotRegisterAsSpringBeans() throws Exception {
        String queryPlanner = Files.readString(Path.of(
                "main/java/com/acme/aicore/app/QueryPlanner.java"));
        String webClientConfig = Files.readString(Path.of(
                "main/java/com/acme/aicore/common/WebClientConfig.java"));
        String cacheConfig = Files.readString(Path.of(
                "main/java/com/acme/aicore/config/CacheConfig.java"));
        String weightedRrfRanking = Files.readString(Path.of(
                "main/java/com/acme/aicore/adapters/ranking/WeightedRrfRanking.java"));
        String mpAwareWeightedRrfRanking = Files.readString(Path.of(
                "main/java/com/acme/aicore/adapters/ranking/MpAwareWeightedRrfRanking.java"));

        assertFalse(queryPlanner.contains("@Component"),
                "Unscanned Acme query planner skeleton must not self-register as a Spring component");
        assertFalse(webClientConfig.contains("@Configuration"),
                "Unscanned Acme WebClient config skeleton must not self-register as Spring configuration");
        assertFalse(cacheConfig.contains("@Configuration"),
                "Unscanned Acme cache config skeleton must not self-register as Spring configuration");
        assertFalse(weightedRrfRanking.contains("@Component"),
                "Unscanned Acme ranking adapter must not self-register as a Spring component");
        assertFalse(mpAwareWeightedRrfRanking.contains("@Component"),
                "Unscanned Acme MP-aware ranking adapter must not self-register as a Spring component");
    }
}
