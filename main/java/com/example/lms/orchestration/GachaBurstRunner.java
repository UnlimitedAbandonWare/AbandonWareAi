package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds bounded, deterministic query variants for the emergency gear.
 */
public class GachaBurstRunner {

    private static final System.Logger LOG = System.getLogger(GachaBurstRunner.class.getName());

    private final GoldenRatioProfile profile;

    public GachaBurstRunner(GoldenRatioProfile profile) {
        this.profile = profile == null ? GoldenRatioProfile.defaults() : profile;
    }

    public List<String> variants(String query, int max) {
        String q = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        if (q.isBlank() || max <= 0) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> suffixes = List.of(
                "official evidence",
                "contradiction check",
                "long tail source",
                "authority comparison",
                "missing recall");
        for (String suffix : suffixes) {
            out.add((q + " " + suffix).trim());
            if (out.size() >= max) {
                break;
            }
        }
        out.remove(q);
        List<String> variants = new ArrayList<>(out);
        try {
            TraceStore.put("dualGear.gacha.variants.count", variants.size());
            TraceStore.put("dualGear.gacha.profile.baseline.temperature", profile.baseline().temperature());
            TraceStore.put("dualGear.gacha.queryHash12", SafeRedactor.hash12(q));
        } catch (Throwable ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "GachaBurstRunner trace skipped errorType="
                            + SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
        return variants;
    }
}
