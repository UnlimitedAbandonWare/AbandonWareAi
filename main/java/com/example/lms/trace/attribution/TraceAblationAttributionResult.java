package com.example.lms.trace.attribution;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Trace-Ablation Attribution (TAA) result.
 *
 * <p>
 * This is a debugging artifact that ranks the most likely contributors to a degraded
 * outcome (e.g. COMPRESSION/STRIKE/BYPASS, starvation failsoft, blank auxiliary output).
 *
 * <p>
 * NOTE: This is <b>not</b> meant to be a perfect causal model. It is a best-effort
 * attribution over observable trace evidence.
 */
public record TraceAblationAttributionResult(
        String version,
        String outcome,
        double outcomeRisk,
        List<Contributor> contributors,
        List<Beam> beams,
        Map<String, Object> debug
) {

    public TraceAblationAttributionResult {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
        beams = beams == null ? List.of() : List.copyOf(beams);
        debug = debug == null ? Collections.emptyMap() : Collections.unmodifiableMap(debug);
    }

    /** Ranked contributor. contribution is normalized to [0..1]. */
    public record Contributor(
            String id,
            String group,
            String title,
            double contribution,
            double traceScore,
            List<String> evidence,
            List<String> recommendations
    ) {
        public Contributor {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        }
    }

    /** One beam-search hypothesis (Self-Ask chain). */
    public record Beam(
            double score,
            double weight,
            List<QaStep> steps
    ) {
        public Beam {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** One Self-Ask step. */
    public record QaStep(
            String question,
            String answer,
            double score,
            List<String> evidence
    ) {
        public QaStep {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
