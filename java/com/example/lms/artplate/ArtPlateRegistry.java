package com.example.lms.artplate;

import org.springframework.stereotype.Component;
import java.util.*;



/**
 * In-code registry to start (YAML externalization can follow via @ConfigurationProperties).
 */
@Component
public class ArtPlateRegistry {

    private final Map<String, ArtPlateSpec> plates = new LinkedHashMap<>();

    public ArtPlateRegistry() {
        // AP1: Authority web-first (gov/edu/official + high evidence)
        plates.put("AP1_AUTH_WEB", new ArtPlateSpec(
            "AP1_AUTH_WEB","rag",
            8, 4, false, false,
            2500, 1500,
            java.util.List.of("go.kr","ac.kr","who.int","wikipedia.org","gov","edu"),
            0.35, 0.60,
            true, false, true,
            java.util.List.of("gpt-5-mini","llama-3.1-8b-instant"),
            true, 2, 1,
            0.8, 0.3, 1.0, 0.2
        ));

        // AP2: Freshness / breaking-news oriented
        plates.put("AP2_FRESH_WEB", new ArtPlateSpec(
            "AP2_FRESH_WEB","rag",
            10, 3, false, false,
            2200, 1200,
            java.util.List.of(), // no domain restrict, rely on authority floors
            0.30, 0.45,
            true, false, true,
            java.util.List.of("gpt-5-mini"),
            true, 2, 2,
            0.5, 0.6, 0.9, 0.3
        ));

        // AP3: Dense-vector RAG heavy with strong re-ranking
        plates.put("AP3_VEC_DENSE", new ArtPlateSpec(
            "AP3_VEC_DENSE","rag",
            3, 12, false, false,
            1200, 2200,
            java.util.List.of(),
            0.25, 0.50,
            true, true, true,
            java.util.List.of("gpt-5-mini","llama-3.1-8b-instant"),
            true, 2, 2,
            0.6, 0.5, 1.0, 0.6
        ));

        // AP4: Memory-first priming (strict authority & consensus)
        plates.put("AP4_MEM_HARVEST", new ArtPlateSpec(
            "AP4_MEM_HARVEST","chat",
            2, 6, true, false,
            800, 1600,
            java.util.List.of(),
            0.30, 0.70,
            true, false, true,
            java.util.List.of("gpt-5-mini"),
            true, 2, 2,
            0.7, 0.3, 0.9, 0.4
        ));

        // AP5: Knowledge-graph reasoning / multi-hop
        plates.put("AP5_KG_REASON", new ArtPlateSpec(
            "AP5_KG_REASON","rag",
            4, 8, false, true,
            1500, 2000,
            java.util.List.of(),
            0.25, 0.55,
            true, false, true,
            java.util.List.of("gpt-5-mini"),
            true, 2, 2,
            0.7, 0.4, 1.0, 0.7
        ));

        // AP6: Long-form polish (two-pass)
        plates.put("AP6_LONG_POLISH", new ArtPlateSpec(
            "AP6_LONG_POLISH","longform",
            6, 6, false, false,
            2000, 2000,
            java.util.List.of(),
            0.20, 0.50,
            true, true, true,
            java.util.List.of("gpt-5-mini","gpt-5-pro"),
            true, 2, 2,
            0.6, 0.4, 0.8, 0.5
        ));

        // AP7: Safe fallback (graceful "정보 없음" path)
        plates.put("AP7_SAFE_FALLBACK", new ArtPlateSpec(
            "AP7_SAFE_FALLBACK","chat",
            2, 2, false, false,
            600, 600,
            java.util.List.of("wikipedia.org","who.int","go.kr","ac.kr"),
            0.10, 0.60,
            false, false, false,
            java.util.List.of("gpt-5-mini"),
            true, 1, 1,
            0.8, 0.2, 0.9, 0.3
        ));

        // AP8: Counter-factual exploration (contrasts/opposition)
        plates.put("AP8_CONTRA_FACT", new ArtPlateSpec(
            "AP8_CONTRA_FACT","rag",
            5, 10, false, false,
            1800, 2200,
            java.util.List.of(),
            0.35, 0.55,
            true, false, true,
            java.util.List.of("gpt-5-mini"),
            true, 2, 2,
            0.7, 0.6, 1.0, 0.6
        ));

        // AP9: Cost saver (tight budgets, small model)
        plates.put("AP9_COST_SAVER", new ArtPlateSpec(
            "AP9_COST_SAVER","chat",
            2, 3, false, false,
            500, 800,
            java.util.List.of(),
            0.15, 0.40,
            true, false, false,
            java.util.List.of("gpt-5-mini"),
            false, 1, 1,
            0.5, 0.3, 0.7, 0.3
        ));
    }

    public Collection<ArtPlateSpec> all() { return plates.values(); }
    public Optional<ArtPlateSpec> get(String id) { return Optional.ofNullable(plates.get(id)); }
}