package com.example.lms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG related configuration properties.
 *
 * <p>Goal: externalize ranking knobs (RRF weights/constant) out of code so that
 * orchestration logic stays deterministic and reproducible.
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Rrf rrf = new Rrf();

    @Data
    public static class Rrf {
        /**
         * RRF constant k0 (commonly 60).
         */
        private int constant = 60;

        // [FUTURE_TECH FIX] when WEB results are "rich" (many sources), downweight stale VECTOR/BM25
        private int webRichThreshold = 3;
        private double webWeightWhenWebRich = 0.7;
        private double vectorWeightWhenWebRich = 0.15;
        private double bm25WeightWhenWebRich = 0.15;

        private Weight weight = new Weight();

        @Data
        public static class Weight {
            private double web = 1.0;
            private double vector = 0.8;
            private double bm25 = 0.9;
            private double kg = 0.7;
        }
    }
}
