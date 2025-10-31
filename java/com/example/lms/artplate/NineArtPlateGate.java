package com.example.lms.artplate;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.List;




@Component
public class NineArtPlateGate {

    private static final Logger log = LoggerFactory.getLogger(NineArtPlateGate.class);

    private final ArtPlateRegistry reg;

    public NineArtPlateGate(ArtPlateRegistry reg) {
        this.reg = reg;
    }

    /**
     * Decide plate based on a light-weight PlateContext derived from the current request/session.
     * Keep logic simple; evolver can override via A/B.
     */
    public ArtPlateSpec decide(PlateContext ctx) {
        // Prefer high authority web when evidence is already decent
        if (ctx.authority() > 0.65 && ctx.evidenceCount() >= 2) {
            return reg.get("AP1_AUTH_WEB").orElseThrow();
        }
        // Repeated session questions + memory available
        if (ctx.sessionRecur() > 2 && ctx.memoryGate() > 0.5) {
            return reg.get("AP4_MEM_HARVEST").orElseThrow();
        }
        // Vector recall need
        if (ctx.vectorGate() > ctx.webGate() && ctx.recallNeed() > 0.6) {
            return reg.get("AP3_VEC_DENSE").orElseThrow();
        }
        // If signals indicate noise or low confidence, take safe fallback
        if (ctx.noisy() || ctx.authority() < 0.25) {
            return reg.get("AP7_SAFE_FALLBACK").orElseThrow();
        }

            // Heuristic: if web search is explicitly requested and webGate is strong, prefer AP1
            if (ctx.useWeb() && ctx.webGate() >= 0.55 && ctx.recallNeed() <= 0.60) {
                return reg.get("AP1_AUTH_WEB").orElseThrow();
            }

        // Default to a frugal plate
        return reg.get("AP9_COST_SAVER").orElseThrow();
    }

    public void apply(Object chain, ArtPlateSpec p) {
        // Reflection to avoid hard dependency on HybridRetriever signatures.
        invokeIfExists(chain, "setWebTopK", int.class, p.webTopK());
        invokeIfExists(chain, "setVectorTopK", int.class, p.vecTopK());
        invokeIfExists(chain, "setBudgetsMs", int.class, p.webBudgetMs(), int.class, p.vecBudgetMs());
        invokeIfExists(chain, "enableMemory", boolean.class, p.allowMemory());
        invokeIfExists(chain, "enableKg", boolean.class, p.kgOn());
        invokeIfExists(chain, "setDomainAllow", java.util.List.class, p.domainAllow());
        invokeIfExists(chain, "setMinEvidence", int.class, p.minEvidence(), int.class, p.minDistinctSources());
        invokeIfExists(chain, "enableCrossEncoder", boolean.class, p.crossEncoderOn());
        log.debug("[NineArtPlateGate] Applied {} to {} (topK web={}, vec={}, budget web={}ms, vec={}ms)",
                p.id(), chain.getClass().getSimpleName(), p.webTopK(), p.vecTopK(), p.webBudgetMs(), p.vecBudgetMs());
    }

    private void invokeIfExists(Object target, String methodName, Class<?> t1, Object v1) {
        try {
            Method m = target.getClass().getMethod(methodName, t1);
            m.invoke(target, v1);
        } catch (NoSuchMethodException ignore) {
        } catch (Exception e) {
            log.warn("Failed invoking {}.{}({}): {}", target.getClass().getSimpleName(), methodName, t1.getSimpleName(), e.toString());
        }
    }
    private void invokeIfExists(Object target, String methodName, Class<?> t1, Object v1, Class<?> t2, Object v2) {
        try {
            Method m = target.getClass().getMethod(methodName, t1, t2);
            m.invoke(target, v1, v2);
        } catch (NoSuchMethodException ignore) {
        } catch (Exception e) {
            log.warn("Failed invoking {}.{}({},{}): {}", target.getClass().getSimpleName(), methodName, t1.getSimpleName(), t2.getSimpleName(), e.toString());
        }
    }
}