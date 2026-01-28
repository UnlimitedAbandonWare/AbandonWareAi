package service.plan;

import trace.TraceContext;
import java.nio.file.Path;

/**
 * Selects plan based on TraceContext flags.
 */
public class PlanSelector {
    public Path select(Path plansDir) {
        if (TraceContext.isBrave()) {
            return plansDir.resolve("brave.v1.yaml");
        }
        String token = TraceContext.getRuleBreakToken();
        if (token != null && !token.isEmpty()) {
            return plansDir.resolve("rulebreak.v1.yaml");
        }
        return plansDir.resolve("safe_autorun.v1.yaml");
    }
}