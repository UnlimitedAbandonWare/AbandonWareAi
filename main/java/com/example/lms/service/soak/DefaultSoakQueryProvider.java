package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default SoakQueryProvider backed by application.yml.
 *
 * <p>
 * NOTE: "naver-bing-fixed10" is kept as an alias only for backward
 * compatibility. New deployments should use "naver-fixed10" or
 * "naver-brave-fixed10".
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultSoakQueryProvider implements SoakQueryProvider {

    private final SoakSeedProperties seeds;

    public DefaultSoakQueryProvider(SoakSeedProperties seeds) {
        this.seeds = seeds;
    }
    @Override
    public List<String> queries(String topic) {
        return seeds == null ? java.util.Collections.emptyList() : seeds.queries(topic);
    }
}