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
        if (chain == null || p == null) return;

        // Reflection to avoid hard dependency on HybridRetriever signatures.
        // NOTE: Many components have moved to request-scoped "metadata hints" instead of singleton setters.
        boolean any = false;
        any |= invokeIfExists(chain, "setWebTopK", int.class, p.webTopK());
        any |= invokeIfExists(chain, "setVectorTopK", int.class, p.vecTopK());
        any |= invokeIfExists(chain, "setBudgetsMs", int.class, p.webBudgetMs(), int.class, p.vecBudgetMs());
        any |= invokeIfExists(chain, "enableMemory", boolean.class, p.allowMemory());
        any |= invokeIfExists(chain, "enableKg", boolean.class, p.kgOn());
        any |= invokeIfExists(chain, "setDomainAllow", java.util.List.class, p.domainAllow());
        any |= invokeIfExists(chain, "setMinEvidence", int.class, p.minEvidence(), int.class, p.minDistinctSources());
        any |= invokeIfExists(chain, "enableCrossEncoder", boolean.class, p.crossEncoderOn());

        if (any) {
            log.debug("[NineArtPlateGate] Applied {} to {} (topK web={}, vec={}, budget web={}ms, vec={}ms)",
                    p.id(), chain.getClass().getSimpleName(), p.webTopK(), p.vecTopK(), p.webBudgetMs(), p.vecBudgetMs());
        } else {
            log.warn("[NineArtPlateGate] Plate {} NOT applied to {} (no compatible setters found).", p.id(),
                    chain.getClass().getSimpleName());
        }
    }

    private boolean invokeIfExists(Object target, String methodName, Class<?> t1, Object v1) {
        try {
            Method m = target.getClass().getMethod(methodName, t1);
            m.invoke(target, v1);
            return true;
        } catch (NoSuchMethodException ignore) {
            return false;
        } catch (Exception e) {
            log.warn("Failed invoking {}.{}({}): {}", target.getClass().getSimpleName(), methodName, t1.getSimpleName(), e.toString());
            return false;
        }
    }
    private boolean invokeIfExists(Object target, String methodName, Class<?> t1, Object v1, Class<?> t2, Object v2) {
        try {
            Method m = target.getClass().getMethod(methodName, t1, t2);
            m.invoke(target, v1, v2);
            return true;
        } catch (NoSuchMethodException ignore) {
            return false;
        } catch (Exception e) {
            log.warn("Failed invoking {}.{}({},{}): {}", target.getClass().getSimpleName(), methodName, t1.getSimpleName(), t2.getSimpleName(), e.toString());
            return false;
        }
    }
}