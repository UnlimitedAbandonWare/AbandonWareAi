package com.example.lms.service.routing.plan;

import com.abandonware.ai.agent.integrations.TextUtils;
import com.example.lms.search.SmartQueryPlanner;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Centralised routing/query planning facade.
 *
 * <p>Why this exists:
 * <ul>
 *   <li>Multiple components (workflow, retrievers, guards) need planned queries.</li>
 *   <li>Repeated planner calls can drift (especially with LLM-backed components) and waste budget.</li>
 *   <li>This service provides slice-aware caching + prefix-stable extension.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoutingPlanService {

    private static final String NS = "queryPlan";

    private final SmartQueryPlanner smartQueryPlanner;
    private final RouterDecisionCache decisionCache;
    private final EvidenceSlicePolicy slicePolicy;

    /** Cached value wrapper so we can extend in a prefix-stable manner. */
    private record PlanEnvelope(List<String> planned, int computedWithMax) {
    }

    public List<String> plan(String userPrompt) {
        return plan(userPrompt, null, 2);
    }

    /**
     * Plan search queries for a user prompt.
     *
     * <p>Note: {@code maxQueries} is treated as a <i>planner hint</i>, mirroring {@link SmartQueryPlanner}'s
     * domain-specific capping (e.g., GENERAL enforces a minimum diversity).
     */
    public List<String> plan(String userPrompt, @Nullable String assistantDraft, int maxQueries) {
        int requested = clamp(maxQueries, 1, 32);

        String input = Objects.toString(userPrompt, "").trim();
        String normKey = TextUtils.normalizeQueryKey(input);
        String draftHash = TextUtils.sha1(Objects.toString(assistantDraft, ""));

        String decisionKey = TextUtils.sha1("plan|" + normKey + "|draft=" + draftHash);
        String sliceFp = slicePolicy.fingerprint(
                NS,
                normKey,
                Map.of(
                        "draft", draftHash,
                        "kind", "plan"));

        PlanEnvelope env = decisionCache.getOrCompute(
                NS,
                decisionKey,
                sliceFp,
                PlanEnvelope.class,
                () -> new PlanEnvelope(safeList(smartQueryPlanner.plan(input, assistantDraft, requested)), requested));

        // If the caller asked for more than we previously computed for, attempt to extend the plan.
        int neededCap = desiredPlannerCap(env.planned, requested);
        if (env.planned.size() < neededCap && requested > env.computedWithMax) {
            List<String> recomputed = safeList(smartQueryPlanner.plan(input, assistantDraft, requested));
            List<String> merged = mergePrefixStable(env.planned, recomputed);
            PlanEnvelope updated = new PlanEnvelope(merged, requested);
            decisionCache.put(NS, decisionKey, sliceFp, updated);
            env = updated;
        }

        return applyCap(env.planned, requested);
    }

    /**
     * PAIRING 등에서 subject anchor를 강제 포함시키는 플래너 래퍼.
     */
    public List<String> planAnchored(
            String userPrompt,
            String subjectPrimary,
            @Nullable String subjectAlias,
            @Nullable String assistantDraft,
            int maxQueries) {

        int requested = clamp(maxQueries, 1, 8);
        String input = Objects.toString(userPrompt, "").trim();
        String normKey = TextUtils.normalizeQueryKey(input);
        String draftHash = TextUtils.sha1(Objects.toString(assistantDraft, ""));
        String primary = Objects.toString(subjectPrimary, "").trim();
        String alias = Objects.toString(subjectAlias, "").trim();

        String decisionKey = TextUtils.sha1("anchored|" + normKey + "|p=" + primary + "|a=" + alias + "|draft=" + draftHash);
        String sliceFp = slicePolicy.fingerprint(
                NS,
                normKey,
                Map.of(
                        "draft", draftHash,
                        "primary", primary,
                        "alias", alias,
                        "kind", "anchored"));

        PlanEnvelope env = decisionCache.getOrCompute(
                NS,
                decisionKey,
                sliceFp,
                PlanEnvelope.class,
                () -> new PlanEnvelope(
                        safeList(smartQueryPlanner.planAnchored(input, primary, (alias.isBlank() ? null : alias), assistantDraft, requested)),
                        requested));

        // Anchored plans are strictly capped (<=4) by the underlying planner.
        int cap = Math.max(1, Math.min(4, requested));
        if (env.planned.size() > cap) {
            return List.copyOf(env.planned.subList(0, cap));
        }
        return List.copyOf(env.planned);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<String> safeList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        return List.copyOf(in);
    }

    /**
     * For GENERAL domains SmartQueryPlanner enforces minimum diversity (>=6). We approximate the
     * cap rule without re-running domain detection by using the returned list size.
     */
    private static int desiredPlannerCap(List<String> planned, int requestedMax) {
        if (planned == null) {
            return clamp(requestedMax, 1, 8);
        }
        // Heuristic: SPECIALISED caps at 4, so >=6 strongly implies GENERAL.
        if (planned.size() >= 6) {
            return Math.min(8, Math.max(6, requestedMax));
        }
        return Math.max(1, Math.min(4, requestedMax));
    }

    private static List<String> applyCap(List<String> planned, int requestedMax) {
        if (planned == null || planned.isEmpty()) {
            return List.of();
        }
        int cap = desiredPlannerCap(planned, requestedMax);
        if (planned.size() <= cap) {
            return List.copyOf(planned);
        }
        return List.copyOf(planned.subList(0, cap));
    }

    private static List<String> mergePrefixStable(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (base != null) {
            for (String q : base) {
                String k = TextUtils.normalizeQueryKey(q);
                if (k.isBlank()) {
                    continue;
                }
                if (seen.add(k)) {
                    out.add(q);
                }
            }
        }

        if (extra != null) {
            for (String q : extra) {
                String k = TextUtils.normalizeQueryKey(q);
                if (k.isBlank()) {
                    continue;
                }
                if (seen.add(k)) {
                    out.add(q);
                }
            }
        }

        return List.copyOf(out);
    }
}
