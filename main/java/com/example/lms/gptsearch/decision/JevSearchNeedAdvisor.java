package com.example.lms.gptsearch.decision;

import com.example.lms.assist.JevChoiceAdvisor.ChoiceObservation;
import com.example.lms.gptsearch.dto.SearchMode;
import org.springframework.core.env.Environment;

/** Applies a qualified observation only within the caller's existing search permission. */
public final class JevSearchNeedAdvisor {
    public record SearchPermission(boolean callerAllowsWeb, boolean scopedWebAllowed,
            boolean privacyAllowsPublicSearch, boolean priorWebDeny, boolean inferGeneralQuestions) {}
    private final Environment environment;
    public JevSearchNeedAdvisor(Environment environment) { this.environment = environment; }
    public SearchDecision apply(SearchDecision baseline, SearchMode requestedMode,
            ChoiceObservation observation, SearchPermission permission) {
        if (baseline == null || requestedMode != SearchMode.AUTO || permission == null
                || !"on".equals(new com.example.lms.assist.JevSurfacePolicy(environment).resolve("main").mode())
                || !"true".equalsIgnoreCase(environment.getProperty("demo.jev.choice.enabled","false"))
                || !"on".equals(environment.getProperty("demo.jev.seams.search-need","off"))
                || observation == null || !observation.schemaValid() || !observation.confidenceAccepted()
                || observation.probability() == null || observation.probability().isEmpty()) return baseline;
        if (!java.util.Set.of("Question detected triggers light search","No search needed (heuristic)")
                .contains(baseline.reason() == null ? "" : baseline.reason())) return baseline;
        String choice = observation.choice();
        String suffix = switch (choice == null ? "" : choice) {
            case "NONE" -> "web-disable"; case "LIGHT" -> "web-light"; case "DEEP" -> "web-deep"; default -> null;
        };
        if (suffix == null) return baseline;
        double probability = observation.probability().getAsDouble();
        try {
            String raw = environment.getProperty("demo.jev.confidence." + suffix);
            if (raw == null) return baseline;
            double threshold = Double.parseDouble(raw);
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1
                    || !Double.isFinite(probability) || probability < threshold || probability > 1) return baseline;
        } catch (RuntimeException malformed) { return baseline; }
        if ("NONE".equals(choice)) {
            return new SearchDecision(false, baseline.depth(), baseline.providers(), baseline.topK(), "Jev search-need NONE");
        }
        if (!permission.callerAllowsWeb() || !permission.scopedWebAllowed() || !permission.privacyAllowsPublicSearch()
                || permission.priorWebDeny() || (!baseline.shouldSearch() && !permission.inferGeneralQuestions())) return baseline;
        return new SearchDecision(true, SearchDecision.Depth.valueOf(choice), baseline.providers(),
                baseline.topK(), "Jev search-need " + choice);
    }
}
