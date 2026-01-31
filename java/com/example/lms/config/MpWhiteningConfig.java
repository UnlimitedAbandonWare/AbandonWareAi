package com.example.lms.config;

import com.example.lms.llm.LowRankWhiteningTransform;
import com.example.lms.scheduler.WhiteningRefitScheduler;
import com.example.lms.service.rag.mp.LowRankWhiteningStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;



/**
 * Configuration class for MP law based low-rank whitening.  When
 * {@code rag.mp.enabled} is set to true a suite of beans is created
 * including the statistics tracker, transform adapter and scheduled
 * refitter.  All beans are disabled by default to avoid overhead when
 * the feature is not required.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "rag.mp", name = "enabled", havingValue = "true")
public class MpWhiteningConfig {

    @Bean
    public LowRankWhiteningStats lowRankWhiteningStats(
            @Value("${rag.mp.rank:64}") int rank,
            @Value("${rag.mp.sketch-rows:256}") int sketchRows,
            @Value("${rag.mp.min-seen:200}") int minSeen,
            @Value("${rag.mp.eps:1.0e-6}") double eps) {
        return new LowRankWhiteningStats(rank, sketchRows, minSeen, eps);
    }

    @Bean
    public LowRankWhiteningTransform lowRankWhiteningTransform(LowRankWhiteningStats stats) {
        return new LowRankWhiteningTransform(stats);
    }

    @Bean
    public WhiteningRefitScheduler whiteningRefitScheduler(LowRankWhiteningStats stats) {
        return new WhiteningRefitScheduler(stats);
    }
}