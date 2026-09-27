package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class PlanHintApplier {
    private static final System.Logger LOG = System.getLogger(PlanHintApplier.class.getName());
    private static final int MAX_PLAN_TOP_K = 100;
    private static final long MAX_PLAN_BUDGET_MS = 120_000L;
    private static final int MAX_PLAN_MIN_CITATIONS = 100;
    private static final String FIELD_APPLIED = "fieldApplied";
    private static final String FIELD_REJECTED = "fieldRejected";

    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${plans.cache.ttl-ms:5000}")
    private long cacheTtlMs;

    private record LoadedPlan(PlanHints hints, PlanExecutionSpec spec) {}

    private record CacheEntry(PlanHints hints, PlanExecutionSpec spec, long loadedAtMs) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PlanHints load(String planId) {
        String normalized = normalizePlanId(planId);
        CacheEntry hit = cache.get(normalized);
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.loadedAtMs) <= cacheTtlMs) {
            traceFieldDecisions(hit.hints);
            return hit.hints;
        }

        LoadedPlan loaded = loadInternal(normalized);
        cache.put(normalized, new CacheEntry(loaded.hints(), loaded.spec(), now));
        traceFieldDecisions(loaded.hints());
        return loaded.hints();
    }

    /**
     * Additive execution view of {@code plan.when} / {@code plan.pipeline}.
     * Kept out of {@link PlanHints} on purpose: the typed/raw projection is
     * pinned by the boundary tests, while execution gating needs the gated
     * structure separately.
     */
    public PlanExecutionSpec loadExecutionSpec(String planId) {
        String normalized = normalizePlanId(planId);
        CacheEntry hit = cache.get(normalized);
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.loadedAtMs) <= cacheTtlMs) {
            return hit.spec();
        }
        // Same single resource read as load(): the spec is parsed inside
        // loadInternal from the already-read plan node, so a request never
        // performs a second plan-resource lookup for execution semantics.
        LoadedPlan loaded = loadInternal(normalized);
        cache.put(normalized, new CacheEntry(loaded.hints(), loaded.spec(), now));
        return loaded.spec();
    }

    public void applyToGuardContext(PlanHints ph, GuardContext ctx) {
        if (ph == null || ctx == null || ph.isEmpty()) return;

        if (ph.officialSourcesOnly() != null) {
            ctx.setOfficialOnly(ph.officialSourcesOnly());
        }
        if (ph.whitelistProfile() != null && !ph.whitelistProfile().isBlank()) {
            ctx.setDomainProfile(ph.whitelistProfile());
        }
        if (ph.minCitations() != null) {
            ctx.setMinCitations(ph.minCitations());
        }
        if (ph.overdriveEnabled() != null) {
            ctx.putPlanOverride("overdrive.enabled", ph.overdriveEnabled());
        }
        if (ph.onnxEnabled() != null) {
            ctx.putPlanOverride("onnx.enabled", ph.onnxEnabled());
        }

        // Drift removal: plan YAML knobs should deterministically affect runtime behavior.
        // - use_cross_encoder: false  -> disable cross-encoder reranking
        // - rerank_backend: <mode>    -> choose reranker backend (onnx/embedding/noop/auto)
        // - rerank_top_k: <int>       -> override rerank keepN
        // - rerank_ce_top_k / rerank_candidate_k: <int> -> cap the number of candidates scored by CE
        if (ph.useCrossEncoder() != null) {
            ctx.putPlanOverride("rerank.crossEncoder.enabled", ph.useCrossEncoder());
        }
        if (ph.rerankBackend() != null && !ph.rerankBackend().isBlank()) {
            ctx.putPlanOverride("rerank.backend", ph.rerankBackend());
        }
        if (ph.rerankTopK() != null && ph.rerankTopK() > 0) {
            ctx.putPlanOverride("rerank.topK", ph.rerankTopK());
        }
        if (ph.rerankCeTopK() != null && ph.rerankCeTopK() > 0) {
            // canonical + compatibility keys
            ctx.putPlanOverride("rerank.ce.topK", ph.rerankCeTopK());
            ctx.putPlanOverride("rerank.candidateK", ph.rerankCeTopK());
        }

        // knobs: connect "override" → actual consumers (AOP / handlers)
        if (ph.queryBurstCount() != null && ph.queryBurstCount() > 0) {
            // legacy + canonical keys
            ctx.putPlanOverride("expand.queryBurst.count", ph.queryBurstCount());
            ctx.putPlanOverride("queryBurst.count", ph.queryBurstCount());
        }
        Integer selfAskCount = planInt(ph, "expand.selfAsk.count");
        if (selfAskCount != null && selfAskCount > 0) {
            ctx.putPlanOverride("expand.selfAsk.count", selfAskCount);
            ctx.putPlanOverride("selfask.enabled", true);
            ctx.putPlanOverride("selfask.planOverride.reason", "expand.selfAsk.count");
        }
        if (ph.extremeZEnabled() != null && ph.extremeZEnabled()) {
            ctx.putPlanOverride("extremeZ.enabled", ph.extremeZEnabled());
        }

        // passthrough extra plan params/knobs (e.g., extremeZ.budgetMs) into GuardContext overrides
        applyPassthroughOverrides(ph, ctx);

        TraceStore.put("plan.id", ph.planId());
        TraceStore.put("plan.officialOnly", ph.officialSourcesOnly());
        TraceStore.put("plan.minCitations", ph.minCitations());
        TraceStore.put("plan.retrievalOrder", ph.retrievalOrder());
        TraceStore.put("plan.kSchedule", ph.kSchedule());

        TraceStore.put("plan.useCrossEncoder", ph.useCrossEncoder());
        TraceStore.put("plan.rerankBackendHash", SafeRedactor.hashValue(ph.rerankBackend()));
        TraceStore.put("plan.rerankBackendLength", ph.rerankBackend() == null ? 0 : ph.rerankBackend().length());
        TraceStore.put("plan.rerankTopK", ph.rerankTopK());
        TraceStore.put("plan.rerankCeTopK", ph.rerankCeTopK());

        // debugging / RCA
        TraceStore.put("plan.queryBurstCount", ph.queryBurstCount());
        TraceStore.put("plan.extremeZEnabled", ph.extremeZEnabled());
    }

    public void applyToHintsAndMeta(PlanHints ph, OrchestrationHints hints, Map<String, Object> meta) {
        if (ph == null || ph.isEmpty() || hints == null || meta == null) return;

        // allowWeb/allowRag are caps: false will force-disable
        if (ph.allowWeb() != null && !ph.allowWeb()) hints.setAllowWeb(false);
        if (ph.allowRag() != null && !ph.allowRag()) hints.setAllowRag(false);

        if (ph.webTopK() != null && ph.webTopK() > 0) hints.setWebTopK(ph.webTopK());
        if (ph.vecTopK() != null && ph.vecTopK() > 0) hints.setVecTopK(ph.vecTopK());
        if (ph.webBudgetMs() != null && ph.webBudgetMs() > 0) hints.setWebBudgetMs(ph.webBudgetMs());
        if (ph.vecBudgetMs() != null && ph.vecBudgetMs() > 0) hints.setVecBudgetMs(ph.vecBudgetMs());

        if (ph.kSchedule() != null && !ph.kSchedule().isEmpty()) {
            meta.put("kSchedule", ph.kSchedule());
            if (ph.webTopK() == null) {
                Integer k0 = ph.kSchedule().get(0);
                if (k0 != null && k0 > 0) hints.setWebTopK(k0);
            }
        }
        if (ph.retrievalOrder() != null && !ph.retrievalOrder().isEmpty()) {
            meta.put("retrieval.order", ph.retrievalOrder());
        }
        if (ph.kgTopK() != null && ph.kgTopK() > 0) {
            meta.put("kgTopK", ph.kgTopK());
        }
        if (ph.whitelistProfile() != null && !ph.whitelistProfile().isBlank()) {
            meta.put("domainProfile", ph.whitelistProfile());
        }
        if (ph.officialSourcesOnly() != null) {
            meta.put("officialOnly", ph.officialSourcesOnly());
        }
        if (ph.onnxEnabled() != null) {
            meta.put("onnx.enabled", ph.onnxEnabled());
        }
        if (ph.overdriveEnabled() != null) {
            meta.put("overdrive.enabled", ph.overdriveEnabled());
        }

        // --- Drift removal: connect YAML knobs -> OrchestrationHints/meta ---
        // Only disable cross-encoder when explicitly false (fail-soft: true/absent keeps existing behavior)
        if (ph.useCrossEncoder() != null) {
            if (!ph.useCrossEncoder()) {
                hints.setEnableCrossEncoder(false);
            }
            // canonical + compatibility keys for downstream consumers / tracing
            meta.put("rerank.crossEncoder.enabled", ph.useCrossEncoder());
            meta.put("use_cross_encoder", ph.useCrossEncoder());
            meta.put("useCrossEncoder", ph.useCrossEncoder());
            meta.put("cross_encoder.enabled", ph.useCrossEncoder());
            meta.put("crossEncoder.enabled", ph.useCrossEncoder());
        }
        if (ph.rerankBackend() != null && !ph.rerankBackend().isBlank()) {
            meta.put("rerank.backend", ph.rerankBackend());
            meta.put("rerank_backend", ph.rerankBackend());
            meta.put("rerankBackend", ph.rerankBackend());
        }
        if (ph.rerankTopK() != null && ph.rerankTopK() > 0) {
            meta.put("rerank.topK", ph.rerankTopK());
            meta.put("rerank_top_k", ph.rerankTopK());
            meta.put("rerankTopK", ph.rerankTopK());
        }

        // Candidate cap: control how many docs are scored by CE, independently of keepN.
        if (ph.rerankCeTopK() != null && ph.rerankCeTopK() > 0) {
            meta.put("rerank.ce.topK", ph.rerankCeTopK());
            meta.put("rerank.ceTopK", ph.rerankCeTopK());
            meta.put("rerank_ce_top_k", ph.rerankCeTopK());
            meta.put("rerankCeTopK", ph.rerankCeTopK());

            // alternate naming: candidateK
            meta.put("rerank.candidateK", ph.rerankCeTopK());
            meta.put("rerank.candidate_k", ph.rerankCeTopK());
            meta.put("rerank_candidate_k", ph.rerankCeTopK());
            meta.put("rerankCandidateK", ph.rerankCeTopK());
        }

        // plan knobs (for lower layers + tracing)
        if (ph.queryBurstCount() != null && ph.queryBurstCount() > 0) {
            meta.put("expand.queryBurst.count", ph.queryBurstCount());
            meta.put("queryBurst.count", ph.queryBurstCount());
        }
        if (ph.extremeZEnabled() != null) {
            meta.put("extremeZ.enabled", String.valueOf(ph.extremeZEnabled()));
        }
        Integer selfAskCount = planInt(ph, "expand.selfAsk.count");
        if (selfAskCount != null && selfAskCount > 0) {
            meta.put("expand.selfAsk.count", selfAskCount);
            meta.put("selfask.enabled", "true");
            meta.put("enableSelfAsk", "true");
            meta.put("selfask.planOverride.reason", "expand.selfAsk.count");
            hints.setEnableSelfAsk(true);
        }
        applyPassthroughMetadata(ph, meta);

        // keep legacy string hints
        meta.put("allowWeb", String.valueOf(hints.isAllowWeb()));
        meta.put("allowRag", String.valueOf(hints.isAllowRag()));
        meta.put("webTopK", String.valueOf(hints.getWebTopK()));
        meta.put("vecTopK", String.valueOf(hints.getVecTopK()));
        meta.put("webBudgetMs", hints.getWebBudgetMs());
        meta.put("vecBudgetMs", hints.getVecBudgetMs());
        meta.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));

        TraceStore.put("plan.meta.applied", true);
    }


    // passthrough extra plan params/knobs (e.g., extremeZ.*) into GuardContext overrides
    private static final List<String> PASSTHROUGH_PREFIXES = List.of(
            "extremeZ.", "extremez.", "selfask.", "retrieval.", "zero100.",
            "expand.", "overdrive.",
            "llm.", "memory.", "privacy.", "search.", "probe.", "rag."
    );

    private void applyPassthroughOverrides(PlanHints ph, GuardContext ctx) {
        if (ph == null || ctx == null) return;
        Map<String, Object> flat = flatPlanValues(ph);
        if (flat.isEmpty()) return;

        for (Map.Entry<String, Object> e : flat.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            if (!isPassthroughKey(key)) continue;
            // Request-level overrides should win.
            if (ctx.getPlanOverride(key) != null) continue;
            ctx.putPlanOverride(key, e.getValue());
        }
    }

    private void applyPassthroughMetadata(PlanHints ph, Map<String, Object> meta) {
        if (ph == null || meta == null) return;
        Map<String, Object> flat = flatPlanValues(ph);
        if (flat.isEmpty()) return;

        for (Map.Entry<String, Object> e : flat.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            if (!isPassthroughKey(key)) continue;
            meta.putIfAbsent(key, e.getValue());
        }
    }

    private static Integer planInt(PlanHints ph, String key) {
        if (ph == null || key == null || key.isBlank()) return null;
        return asInt(flatPlanValues(ph).get(key));
    }

    private static Map<String, Object> flatPlanValues(PlanHints ph) {
        if (ph == null || ph.raw() == null || ph.raw().isEmpty()) return Map.of();
        Map<String, Object> flat = new java.util.LinkedHashMap<>();
        flattenInto(flat, "", asMap(ph.raw().get("params")));
        flattenInto(flat, "", asMap(ph.raw().get("knobs")));
        flat.putIfAbsent("alias.corrector.enabled", ph.raw().get("alias.corrector.enabled"));
        flat.putIfAbsent("retrieval.web.enabled", ph.raw().get("retrieval.web.enabled"));
        flat.putIfAbsent("retrieval.vector.enabled", ph.raw().get("retrieval.vector.enabled"));
        for (String key : List.of("diversity.dpp.enabled", "retrieval.vector.enabled", "alias.corrector.enabled", "retrieval.web.enabled")) {
            if (!flat.containsKey(key)) continue;
            Boolean enabled = asBool(flat.get(key));
            if (enabled == null) flat.remove(key);
            else flat.put(key, enabled);
        }
        return flat;
    }

    private static boolean isPassthroughKey(String key) {
        if ("diversity.dpp.enabled".equals(key) || "alias.corrector.enabled".equals(key)) return true;
        for (String prefix : PASSTHROUGH_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void flattenInto(Map<String, Object> out, String prefix, Map<String, Object> map) {
        if (map == null || map.isEmpty()) return;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            Object v = e.getValue();
            String fullKey = prefix.isEmpty() ? k : (prefix + k);
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> child = new HashMap<>();
                for (Map.Entry<?, ?> ce : m.entrySet()) {
                    if (ce.getKey() != null) {
                        child.put(String.valueOf(ce.getKey()), ce.getValue());
                    }
                }
                flattenInto(out, fullKey + ".", child);
            } else {
                out.put(fullKey, v);
            }
        }
    }

    // ---------------- loader ----------------

    private LoadedPlan loadInternal(String planId) {
        Resource res = findPlanResource(planId);
        if (res == null || !res.exists()) {
            TraceStore.append("plan.load.miss", safeTraceToken(planId));
            return new LoadedPlan(PlanHints.empty(planId), PlanExecutionSpec.empty("missing_resource"));
        }

        try (InputStream in = res.getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            if (root == null) {
                return new LoadedPlan(PlanHints.empty(planId), PlanExecutionSpec.empty("empty_document"));
            }
            PlanValidationResult validation = validatePlanRoot(planId, root);
            if (!validation.valid()) {
                TraceStore.append("plan.schema.invalid", safeTraceToken(planId + ":" + validation.reason()));
                TraceStore.put("plan.schema.invalid.last", safeTraceToken(planId + ":" + validation.reason()));
                if (!"safe.v1".equals(planId)) {
                    TraceStore.put("plan.schema.fallback", safeTraceToken(planId + "->safe.v1"));
                    return loadInternal("safe.v1");
                }
                return new LoadedPlan(PlanHints.empty("safe.v1"), PlanExecutionSpec.empty("invalid_plan"));
            }
            TraceStore.append("plan.schema.ok", safeTraceToken(planId));

            Map<String, Object> plan = asMap(root.get("plan"));
            Map<String, Object> overrides = asMap(plan.get("overrides"));
            Map<String, Object> props = asMap(overrides.get("properties"));
            Map<String, Object> knobs = asMap(overrides.get("knobs"));
            Map<String, Object> params = asMap(root.get("params"));
            PlanFieldDecisions fieldDecisions = new PlanFieldDecisions();
            List<String> chain = firstNonEmptyStrList(
                    asStrList(root.get("chain")),
                    asStrList(deepGet(root, "chain"))
            );

            Boolean officialOnly = firstNonNullBool(
                    asBool(deepGet(root, "retrieval.officialSourcesOnly")),
                    asBool(deepGet(root, "officialSourcesOnly")),
                    asBool(deepGet(root, "official_sources_only")),
                    asBool(props.get("officialSourcesOnly")),
                    asBool(props.get("official_sources_only")),
                    // legacy/AP plan params variants
                    asBool(params.get("officialSourcesOnly")),
                    asBool(params.get("official_sources_only")),
                    asBool(params.get("officialOnly"))
            );

            String whitelistProfile = firstNonBlank(
                    asString(deepGet(root, "guards.whitelist_profile")),
                    asString(deepGet(root, "guards.whitelistProfile")),
                    asString(deepGet(root, "whitelist_profile")),
                    asString(deepGet(root, "whitelistProfile"))
            );

            List<String> order = firstNonEmptyStrList(
                    asStrList(deepGet(root, "retrieval.order")),
                    asStrList(deepGet(root, "retrievalOrder"))
            );
            order = normalizeOrder(order);

            // Legacy AP plans often express retrieval order via a `chain:` list.
            // Infer order if not explicitly set.
            if ((order == null || order.isEmpty()) && chain != null && !chain.isEmpty()) {
                order = inferOrderFromChain(chain);
            }

            List<Integer> kSchedule = firstNonEmptyIntList(
                    asIntList(deepGet(root, "retrieval.k_schedule")),
                    asIntList(deepGet(root, "k_schedule"))
            );

            Integer webTopK = fieldDecisions.positiveInt("topk.web", MAX_PLAN_TOP_K,
                    deepGet(root, "retrieval.k.web"),
                    deepGet(root, "retrieval.topk.web"),
                    deepGet(root, "k_allocation.web"),
                    deepGet(root, "kAllocation.web"),
                    props.get("naver.search.web-top-k"),
                    props.get("web.search.top-k"),
                    // legacy/AP plan params variants
                    params.get("webTopK"),
                    params.get("web_top_k"),
                    params.get("web-top-k"),
                    params.get("webTopk"),
                    deepGet(params, "topk.web"),
                    deepGet(params, "top_k.web"),
                    deepGet(root, "topk.web"),
                    deepGet(root, "top_k.web")
            );

            Integer vecTopK = fieldDecisions.positiveInt("topk.vector", MAX_PLAN_TOP_K,
                    deepGet(root, "retrieval.k.vector"),
                    deepGet(root, "retrieval.topk.vector"),
                    deepGet(root, "k_allocation.vector"),
                    deepGet(root, "kAllocation.vector"),
                    props.get("rag.vector.top-k"),
                    // legacy/AP plan params variants
                    params.get("vecTopK"),
                    params.get("vectorTopK"),
                    params.get("vector_top_k"),
                    params.get("vector-top-k"),
                    deepGet(params, "topk.vector"),
                    deepGet(params, "top_k.vector"),
                    deepGet(root, "topk.vector"),
                    deepGet(root, "top_k.vector")
            );

            Integer kgTopK = fieldDecisions.positiveInt("topk.kg", MAX_PLAN_TOP_K,
                    deepGet(root, "retrieval.k.kg"),
                    deepGet(root, "retrieval.topk.kg"),
                    deepGet(root, "k_allocation.kg"),
                    deepGet(root, "kAllocation.kg"),
                    // legacy/AP plan params variants
                    params.get("kgTopK"),
                    params.get("kg_top_k"),
                    params.get("kg-top-k"),
                    deepGet(params, "topk.kg"),
                    deepGet(params, "top_k.kg"),
                    deepGet(root, "topk.kg"),
                    deepGet(root, "top_k.kg")
            );

            Object genericTopK = firstNonNullObject(
                    params.get("topk"),
                    params.get("topK"),
                    params.get("top_k"),
                    root.get("topk"),
                    root.get("topK"),
                    root.get("top_k"));
            if (genericTopK != null && !(genericTopK instanceof Map<?, ?>)) {
                fieldDecisions.reject("topk", "ambiguous_generic");
            }

            Long webBudgetMs = fieldDecisions.positiveLong("budget_ms.web", MAX_PLAN_BUDGET_MS,
                    deepGet(root, "budgets.web_ms"),
                    deepGet(root, "budgets.webMs"),
                    deepGet(root, "budget.web_ms"),
                    deepGet(root, "budget.webMs"),
                    params.get("webBudgetMs"),
                    params.get("web_budget_ms"),
                    params.get("web-budget-ms")
            );

            Long vecBudgetMs = fieldDecisions.positiveLong("budget_ms.vector", MAX_PLAN_BUDGET_MS,
                    deepGet(root, "budgets.vec_ms"),
                    deepGet(root, "budgets.vecMs"),
                    deepGet(root, "budget.vec_ms"),
                    deepGet(root, "budget.vecMs"),
                    params.get("vecBudgetMs"),
                    params.get("vec_budget_ms"),
                    params.get("vec-budget-ms"),
                    params.get("vector_budget_ms")
            );

            // legacy/AP plan: a single budget_ms often exists
            Long budgetMs = fieldDecisions.positiveLong("budget_ms", MAX_PLAN_BUDGET_MS,
                    deepGet(root, "budgets.total_ms"),
                    deepGet(root, "budgets.totalMs"),
                    deepGet(root, "budget.total_ms"),
                    deepGet(root, "budget.totalMs"),
                    params.get("budget_ms"),
                    params.get("budgetMs"),
                    params.get("budget")
            );
            if (budgetMs != null) {
                if (webBudgetMs == null && !fieldDecisions.rejected("budget_ms.web")) webBudgetMs = budgetMs;
                if (vecBudgetMs == null && !fieldDecisions.rejected("budget_ms.vector")) vecBudgetMs = budgetMs;
            }

            Integer minCitations = fieldDecisions.positiveInt(
                    "guard.min_citations", MAX_PLAN_MIN_CITATIONS,
                    deepGet(root, "guards.min_citations"),
                    deepGet(root, "gates.citationMin"),
                    deepGet(root, "gates.citation.min"),
                    deepGet(root, "gates.citation_min"),
                    props.get("gate.citation.min"),
                    params.get("minCitations")
            );

            Boolean allowWeb = firstNonNullBool(asBool(params.get("allowWeb")), asBool(deepGet(root, "allowWeb")));
            Boolean allowRag = firstNonNullBool(
                    asBool(params.get("allowRag")),
                    asBool(params.get("allowVector")),
                    asBool(deepGet(root, "allowRag"))
            );

            Boolean vectorOnly = firstNonNullBool(
                    asBool(params.get("vector_only")),
                    asBool(params.get("vectorOnly")),
                    asBool(params.get("vector-only"))
            );

            // Infer allowWeb/allowRag from AP `chain:` if not explicitly set.
            if ((allowWeb == null || allowRag == null) && chain != null && !chain.isEmpty()) {
                boolean hasWeb = chainHasWeb(chain);
                boolean hasVec = chainHasVector(chain);
                if (allowWeb == null) allowWeb = hasWeb;
                if (allowRag == null) allowRag = hasVec;
            }

            if (Boolean.TRUE.equals(vectorOnly)) {
                // vector-only plans must not accidentally fall back to web.
                allowWeb = false;
                allowRag = true;
                if (order == null || order.isEmpty()) {
                    order = List.of("vector");
                }
            }

            // If a plan does not specify retrieval.order / retrievalOrder, inject a sensible default.
            // (EROR_XA_B: safe.v1 produced order [] in traces when unset.)
            if (order == null || order.isEmpty()) {
                order = defaultRetrievalOrder(allowWeb, allowRag, webTopK, vecTopK, kgTopK);
            }

            // Sanitize retrieval order against runtime caps so traces and handler chains stay consistent.
            // - Removes sources that are disabled by allowWeb/allowRag.
            // - Normalizes tokens (rag -> vector) and de-duplicates while preserving order.
            {
                boolean allowWebEff = allowWeb != Boolean.FALSE;
                boolean allowRagEff = allowRag != Boolean.FALSE;
                order = sanitizeRetrievalOrder(order, allowWebEff, allowRagEff, kgTopK);
            }

            Boolean onnxEnabled = firstNonNullBool(
                    asBool(deepGet(root, "onnx_enabled")),
                    asBool(deepGet(root, "onnx.enabled")),
                    asBool(props.get("onnx.enabled")),
                    asBool(deepGet(root, "rerank.onnx.enabled"))
            );

            Boolean overdriveEnabled = firstNonNullBool(
                    asBool(deepGet(root, "overdrive")),
                    asBool(deepGet(root, "overdrive.enabled")),
                    asBool(knobs.get("overdrive.enabled")),
                    asBool(props.get("overdrive.enabled"))
            );

            // knobs: cross-encoder enable (drift removal) + rerank knobs
            Boolean useCrossEncoder = firstNonNullBool(
                    // nested knobs
                    asBool(deepGet(knobs, "cross_encoder.enabled")),
                    asBool(deepGet(knobs, "crossEncoder.enabled")),
                    asBool(deepGet(knobs, "use_cross_encoder")),
                    asBool(deepGet(knobs, "useCrossEncoder")),
                    // flat keys
                    asBool(knobs.get("cross_encoder.enabled")),
                    asBool(knobs.get("crossEncoder.enabled")),
                    asBool(knobs.get("use_cross_encoder")),
                    asBool(knobs.get("useCrossEncoder")),
                    // legacy/AP params
                    asBool(deepGet(params, "cross_encoder.enabled")),
                    asBool(deepGet(params, "crossEncoder.enabled")),
                    asBool(params.get("cross_encoder.enabled")),
                    asBool(params.get("crossEncoder.enabled")),
                    asBool(params.get("use_cross_encoder")),
                    asBool(params.get("useCrossEncoder")),
                    // top-level convenience
                    asBool(deepGet(root, "cross_encoder.enabled")),
                    asBool(root.get("cross_encoder.enabled")),
                    asBool(root.get("use_cross_encoder")),
                    asBool(root.get("useCrossEncoder"))
            );

            String rerankBackend = firstNonBlank(
                    asString(deepGet(knobs, "rerank.backend")),
                    asString(knobs.get("rerank.backend")),
                    asString(knobs.get("rerank_backend")),
                    asString(knobs.get("rerankBackend")),
                    asString(deepGet(params, "rerank.backend")),
                    asString(params.get("rerank.backend")),
                    asString(params.get("rerank_backend")),
                    asString(params.get("rerankBackend")),
                    asString(deepGet(root, "rerank.backend")),
                    asString(root.get("rerank.backend")),
                    asString(root.get("rerank_backend")),
                    asString(root.get("rerankBackend"))
            );

            Integer rerankTopK = firstNonNullInt(
                    asInt(deepGet(knobs, "rerank.topK")),
                    asInt(deepGet(knobs, "rerank.top_k")),
                    asInt(knobs.get("rerank.topK")),
                    asInt(knobs.get("rerank.top_k")),
                    asInt(knobs.get("rerank_top_k")),
                    asInt(knobs.get("rerankTopK")),
                    asInt(knobs.get("rerankTopN")),
                    asInt(deepGet(params, "rerank.topK")),
                    asInt(deepGet(params, "rerank.top_k")),
                    asInt(params.get("rerank.topK")),
                    asInt(params.get("rerank.top_k")),
                    asInt(params.get("rerank_top_k")),
                    asInt(params.get("rerankTopK")),
                    asInt(params.get("rerankTopN")),
                    asInt(deepGet(root, "rerank.topK")),
                    asInt(deepGet(root, "rerank.top_k")),
                    asInt(root.get("rerank.topK")),
                    asInt(root.get("rerank.top_k")),
                    asInt(root.get("rerank_top_k")),
                    asInt(root.get("rerankTopK"))
            );

            // knobs: CE candidate cap (separate from keepN)
            Integer rerankCeTopK = firstNonNullInt(
                    // nested knobs
                    asInt(deepGet(knobs, "rerank.ce.topK")),
                    asInt(deepGet(knobs, "rerank.ceTopK")),
                    asInt(deepGet(knobs, "rerank.ce_top_k")),
                    asInt(deepGet(knobs, "rerank.candidateK")),
                    asInt(deepGet(knobs, "rerank.candidate_k")),
                    // flat keys
                    asInt(knobs.get("rerank.ce.topK")),
                    asInt(knobs.get("rerank.ceTopK")),
                    asInt(knobs.get("rerank.ce_top_k")),
                    asInt(knobs.get("rerank_ce_top_k")),
                    asInt(knobs.get("rerank.candidateK")),
                    asInt(knobs.get("rerankCandidateK")),
                    asInt(knobs.get("rerank.candidate_k")),
                    asInt(knobs.get("rerank_candidate_k")),
                    // legacy/AP params
                    asInt(deepGet(params, "rerank.ce.topK")),
                    asInt(deepGet(params, "rerank.ceTopK")),
                    asInt(deepGet(params, "rerank.ce_top_k")),
                    asInt(deepGet(params, "rerank_candidate_k")),
                    asInt(deepGet(params, "rerank.candidateK")),
                    asInt(deepGet(params, "rerank.candidate_k")),
                    asInt(params.get("rerank.ce.topK")),
                    asInt(params.get("rerank.ceTopK")),
                    asInt(params.get("rerank.ce_top_k")),
                    asInt(params.get("rerank_ce_top_k")),
                    asInt(params.get("rerank.candidateK")),
                    asInt(params.get("rerankCandidateK")),
                    asInt(params.get("rerank.candidate_k")),
                    asInt(params.get("rerank_candidate_k")),
                    // top-level convenience
                    asInt(deepGet(root, "rerank.ce.topK")),
                    asInt(deepGet(root, "rerank.ceTopK")),
                    asInt(deepGet(root, "rerank.ce_top_k")),
                    asInt(deepGet(root, "rerank_candidate_k")),
                    asInt(deepGet(root, "rerank.candidateK")),
                    asInt(deepGet(root, "rerank.candidate_k")),
                    asInt(root.get("rerank.ce.topK")),
                    asInt(root.get("rerank.ceTopK")),
                    asInt(root.get("rerank.ce_top_k")),
                    asInt(root.get("rerank_ce_top_k")),
                    asInt(root.get("rerank_candidate_k")),
                    asInt(root.get("rerankCandidateK"))
            );

            // knobs: query burst / extremeZ
            Integer queryBurstCount = firstNonNullInt(
                    asInt(knobs.get("expand.queryBurst.count")),
                    asInt(knobs.get("queryBurst.count")),
                    asInt(params.get("expand.queryBurst.count")),
                    asInt(params.get("queryBurst.count"))
            );
            Boolean extremeZEnabled = firstNonNullBool(
                    asBool(knobs.get("extremeZ.enabled")),
                    asBool(knobs.get("extremez.enabled")),
                    asBool(params.get("extremeZ.enabled")),
                    asBool(params.get("extremez.enabled"))
            );

            Map<String, Object> raw = new HashMap<>();
            raw.put("resource", res.getFilename());
            raw.put("rootKeys", root.keySet());
            raw.put("dslUnwiredKeys", dslUnwiredKeys(root, plan));
            // passthrough: keep raw params/knobs for optional plan-overrides (e.g., extremeZ.*)
            raw.put("params", params);
            raw.put("knobs", knobs);
            Boolean aliasEnabled = asBool(deepGet(root, "alias.corrector.enabled"));
            if (aliasEnabled != null) raw.put("alias.corrector.enabled", aliasEnabled);
            Boolean webEnabled = asBool(deepGet(root, "retrieval.web.enabled"));
            if (webEnabled != null) raw.put("retrieval.web.enabled", webEnabled);
            Boolean vectorEnabled = asBool(deepGet(root, "retrieval.vector.enabled"));
            if (vectorEnabled != null) raw.put("retrieval.vector.enabled", vectorEnabled);
            raw.put(FIELD_APPLIED, fieldDecisions.applied());
            raw.put(FIELD_REJECTED, fieldDecisions.rejections());

            return new LoadedPlan(new PlanHints(
                    planId,
                    officialOnly,
                    whitelistProfile,
                    order == null ? List.of() : order,
                    webTopK,
                    vecTopK,
                    kgTopK,
                    kSchedule == null ? List.of() : kSchedule,
                    webBudgetMs,
                    vecBudgetMs,
                    minCitations,
                    allowWeb,
                    allowRag,
                    onnxEnabled,
                    overdriveEnabled,
                    useCrossEncoder,
                    rerankBackend,
                    rerankTopK,
                    rerankCeTopK,
                    queryBurstCount,
                    extremeZEnabled,
                    raw
            ), PlanExecutionSpec.parse(plan));
        } catch (Exception e) {
            TraceStore.append("plan.load.error", safeTraceToken(planId + ":load_failed"));
            return new LoadedPlan(PlanHints.empty(planId), PlanExecutionSpec.empty("load_failed"));
        }
    }

    private static String safeTraceToken(String value) {
        return SafeRedactor.safeMessage(value, 160);
    }

    private static void traceFieldDecisions(PlanHints hints) {
        TraceStore.put("plan.fields.applied", fieldDecisionList(hints, FIELD_APPLIED));
        TraceStore.put("plan.fields.rejected", fieldDecisionList(hints, FIELD_REJECTED));
    }

    private static List<String> fieldDecisionList(PlanHints hints, String key) {
        if (hints == null || hints.raw() == null || key == null) {
            return List.of();
        }
        Object value = hints.raw().get(key);
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        for (Object candidate : values) {
            String token = candidate == null ? "" : String.valueOf(candidate).trim();
            if (token.length() <= 80 && token.matches("[a-z0-9_.:-]+")) {
                safe.add(token);
            }
        }
        return List.copyOf(safe);
    }

    public static List<String> dslUnwiredKeys(PlanHints plan) {
        if (plan == null || plan.raw() == null) {
            return List.of();
        }
        Object value = plan.raw().get("dslUnwiredKeys");
        if (!(value instanceof Collection<?> rawKeys)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object key : rawKeys) {
            String safe = safeDslKey(key);
            if (!safe.isBlank()) {
                out.add(safe);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> dslUnwiredKeys(Map<String, Object> root, Map<String, Object> plan) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addDslKey(root, "llm", out, "llm");
        addDslKey(root, "guard", out, "guard");
        addDslKey(root, "alias", out, "alias");
        addDslKey(root, "budget", out, "budget");
        addDslKey(root, "concurrency", out, "concurrency");
        addDslKey(root, "whitening", out, "whitening");
        addDslKey(root, "expansion", out, "expansion");
        addDslKey(root, "fusion", out, "fusion");
        addDslKey(root, "probe", out, "probe");
        addDslKey(plan, "when", out, "plan.when");
        addDslKey(plan, "pipeline", out, "plan.pipeline");
        return List.copyOf(out);
    }

    private static void addDslKey(Map<String, Object> source,
                                  String key,
                                  Set<String> out,
                                  String label) {
        if (source == null || key == null || out == null || label == null) {
            return;
        }
        if (source.containsKey(key)) {
            String safe = safeDslKey(label);
            if (!safe.isBlank()) {
                out.add(safe);
            }
        }
    }

    private static String safeDslKey(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .trim()
                .replaceAll("[^A-Za-z0-9_.-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private record PlanValidationResult(boolean valid, String reason) {
        static PlanValidationResult ok() {
            return new PlanValidationResult(true, "");
        }

        static PlanValidationResult invalid(String reason) {
            return new PlanValidationResult(false, reason);
        }
    }

    private static PlanValidationResult validatePlanRoot(String planId, Map<String, Object> root) {
        String declaredId = firstNonBlank(
                asString(deepGet(root, "plan.id")),
                asString(root.get("id")),
                asString(root.get("name"))
        );
        if (declaredId == null) {
            return PlanValidationResult.invalid("missing_id");
        }
        if (!isSafePlanToken(declaredId)) {
            return PlanValidationResult.invalid("malformed_id");
        }
        String secretIssue = findForbiddenSensitiveKey("", root);
        if (secretIssue != null) {
            return PlanValidationResult.invalid(secretIssue);
        }
        return PlanValidationResult.ok();
    }

    private static boolean isSafePlanToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        return trimmed.length() <= 96 && trimmed.matches("[A-Za-z0-9_.-]+");
    }

    private static String findForbiddenSensitiveKey(String path, Object value) {
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(e.getKey());
                String childPath = path == null || path.isBlank() ? key : path + "." + key;
                Object child = e.getValue();
                if (isSensitivePlanKey(key)) {
                    return "forbidden_sensitive_key:" + childPath;
                }
                String nested = findForbiddenSensitiveKey(childPath, child);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (value instanceof Collection<?> c) {
            int i = 0;
            for (Object child : c) {
                String nested = findForbiddenSensitiveKey(path + "[" + i + "]", child);
                if (nested != null) {
                    return nested;
                }
                i++;
            }
        }
        return null;
    }

    private static boolean isSensitivePlanKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("ownertoken")
                || normalized.equals("admintoken")
                || normalized.equals("apikey")
                || normalized.equals("clientsecret")
                || normalized.equals("secret")
                || normalized.equals("secretkey")
                || normalized.equals("secrettoken")
                || normalized.endsWith("secret");
    }

    private Resource findPlanResource(String planId) {
        String id = normalizePlanId(planId);
        Resource r1 = resourceLoader.getResource("classpath:plans/" + id + ".yaml");
        if (r1.exists()) return r1;
        Resource r2 = resourceLoader.getResource("classpath:plans/" + id + ".yml");
        if (r2.exists()) return r2;

        // allow passing full name (e.g., safe.v1.yaml)
        Resource r3 = resourceLoader.getResource("classpath:plans/" + id);
        if (r3.exists()) return r3;

        return r1;
    }

    // ---------------- normalization ----------------

    private static String normalizePlanId(String planId) {
        String raw = (planId == null) ? "" : planId.trim();
        if (raw.isBlank()) return "safe.v1";

        raw = raw.replace('\\', '/');
        if (raw.startsWith("plans/")) raw = raw.substring("plans/".length());
        if (raw.endsWith(".yaml")) raw = raw.substring(0, raw.length() - 5);
        if (raw.endsWith(".yml")) raw = raw.substring(0, raw.length() - 4);

        String lower = raw.toLowerCase(Locale.ROOT);

        // direct file ids
        if (lower.matches(".*\\.v\\d+$")) return isSafePlanToken(lower) ? lower : "safe.v1";

        // aliases / modes
        if (lower.equals("s1") || lower.equals("safe") || lower.equals("default")) return "safe.v1";
        if (lower.equals("s2") || lower.equals("brave")) return "brave.v1";
        // AnswerMode aliases
        if (lower.equals("fact")) return "safe.v1";
        if (lower.equals("creative")) return "brave.v1";
        if (lower.equals("balanced") || lower.equals("all_rounder") || lower.equals("all-rounder") || lower.equals("allrounder")) {
            return "safe_autorun.v1";
        }
        if (lower.contains("autorun")) return "safe_autorun.v1";
        if (lower.contains("kg_first")) return "kg_first.v1";
        if (lower.contains("recency")) return "recency_first.v1";
        if (lower.contains("ap9")) return "ap9_cost_saver.v1";
        if (lower.equals("zero100") || lower.equals("zero-100") || lower.equals("zero_100")
                || lower.contains("zero100")) {
            return "zero100.v1";
        }
        if (lower.contains("zero_break") || lower.contains("zero-break") || lower.contains("zerobreak")
                || lower.equals("zero")) {
            return "zero_break.v1";
        }
        if (lower.contains("rule")) return "rulebreak.v1";
        if (lower.contains("hyper")) return "hyper_nova.v1";
        if (lower.contains("brave")) return "brave.v1";

        return "safe.v1";
    }

    private static List<String> defaultRetrievalOrder(Boolean allowWeb, Boolean allowRag,
            Integer webTopK, Integer vecTopK, Integer kgTopK) {
        List<String> out = new ArrayList<>();

        // allowWeb/allowRag caps or topK values based inference
        if (allowWeb != Boolean.FALSE && (webTopK == null || webTopK > 0)) {
            out.add("web");
        }
        if (allowRag != Boolean.FALSE && (vecTopK == null || vecTopK > 0)) {
            out.add("vector");
        }
        if (kgTopK != null && kgTopK > 0) {
            out.add("kg");
        }

        // 최소 1개 보장
        if (out.isEmpty()) {
            if (allowWeb != Boolean.FALSE) out.add("web");
            else if (allowRag != Boolean.FALSE) out.add("vector");
            else out.add("kg");
        }

        return normalizeOrder(out);
    }

    /**
     * Sanitize retrieval order against runtime caps so traces and handler chains stay consistent.
     *
     * <ul>
     *   <li>Removes sources that are disabled by allowWeb/allowRag</li>
     *   <li>Normalizes tokens (rag -&gt; vector)</li>
     *   <li>De-duplicates while preserving order</li>
     * </ul>
     */
    private static List<String> sanitizeRetrievalOrder(List<String> order,
            boolean allowWeb,
            boolean allowRag,
            Integer kgTopK) {

        List<String> norm = normalizeOrder(order);
        if (norm == null || norm.isEmpty()) {
            return defaultRetrievalOrder(allowWeb, allowRag, null, null, kgTopK);
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : norm) {
            if (s == null) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.equals("rag")) t = "vector";
            if (!allowWeb && t.equals("web")) continue;
            if (!allowRag && t.equals("vector")) continue;
            // If KG topK is explicitly 0, omit it (plan may still list it).
            if (t.equals("kg") && kgTopK != null && kgTopK <= 0) continue;
            out.add(t);
        }

        if (out.isEmpty()) {
            return defaultRetrievalOrder(allowWeb, allowRag, null, null, kgTopK);
        }
        return new ArrayList<>(out);
    }

    private static List<String> normalizeOrder(List<String> order) {
        if (order == null || order.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : order) {
            if (s == null) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.equals("rag")) t = "vector";
            out.add(t);
        }
        return out;
    }

    // ---------------- legacy/AP plan helpers ----------------

    private static List<String> inferOrderFromChain(List<String> chain) {
        if (chain == null || chain.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String step : chain) {
            if (step == null) continue;
            String t = step.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.startsWith("retrieve_web")) {
                out.add("web");
            } else if (t.startsWith("retrieve_vector") || t.startsWith("retrieve_vec") || t.startsWith("retrieve_rag")) {
                out.add("vector");
            } else if (t.startsWith("retrieve_kg")) {
                out.add("kg");
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean chainHasWeb(List<String> chain) {
        if (chain == null) return false;
        for (String step : chain) {
            if (step == null) continue;
            String t = step.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("retrieve_web")) return true;
        }
        return false;
    }

    private static boolean chainHasVector(List<String> chain) {
        if (chain == null) return false;
        for (String step : chain) {
            if (step == null) continue;
            String t = step.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("retrieve_vector") || t.startsWith("retrieve_vec") || t.startsWith("retrieve_rag")) return true;
        }
        return false;
    }

    // ---------------- tiny utils ----------------

    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static Object deepGet(Map<String, Object> root, String dottedPath) {
        if (root == null || dottedPath == null) return null;
        String[] parts = dottedPath.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = ((Map<?, ?>) m).get(part);
        }
        return cur;
    }

    private static Object firstNonNullObject(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private record ParsedIntegral(BigInteger value, String error) {
        static ParsedIntegral valid(BigInteger value) {
            return new ParsedIntegral(value, "");
        }

        static ParsedIntegral invalid(String error) {
            return new ParsedIntegral(null, error);
        }
    }

    private static ParsedIntegral parseIntegral(Object raw) {
        if (raw == null) {
            return ParsedIntegral.invalid("invalid_number");
        }
        try {
            String text = raw instanceof BigDecimal decimal
                    ? decimal.toPlainString()
                    : String.valueOf(raw).trim();
            if (text.isBlank()) {
                return ParsedIntegral.invalid("invalid_number");
            }
            return ParsedIntegral.valid(new BigDecimal(text).toBigIntegerExact());
        } catch (NumberFormatException | ArithmeticException invalid) {
            return ParsedIntegral.invalid("invalid_number");
        }
    }

    private static final class PlanFieldDecisions {
        private final LinkedHashSet<String> applied = new LinkedHashSet<>();
        private final LinkedHashSet<String> rejected = new LinkedHashSet<>();

        Integer positiveInt(String field, int max, Object... candidates) {
            Object raw = firstNonNullObject(candidates);
            if (raw == null) return null;
            ParsedIntegral parsed = parseIntegral(raw);
            if (parsed.value() == null) {
                reject(field, parsed.error());
                return null;
            }
            if (parsed.value().compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || parsed.value().compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                reject(field, "overflow");
                return null;
            }
            int value = parsed.value().intValue();
            if (value <= 0) {
                reject(field, "non_positive");
                return null;
            }
            if (value > max) {
                reject(field, "out_of_range");
                return null;
            }
            applied.add(field);
            return value;
        }

        Long positiveLong(String field, long max, Object... candidates) {
            Object raw = firstNonNullObject(candidates);
            if (raw == null) return null;
            ParsedIntegral parsed = parseIntegral(raw);
            if (parsed.value() == null) {
                reject(field, parsed.error());
                return null;
            }
            if (parsed.value().compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || parsed.value().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                reject(field, "overflow");
                return null;
            }
            long value = parsed.value().longValue();
            if (value <= 0L) {
                reject(field, "non_positive");
                return null;
            }
            if (value > max) {
                reject(field, "out_of_range");
                return null;
            }
            applied.add(field);
            return value;
        }

        void reject(String field, String reason) {
            rejected.add(field + ":" + reason);
        }

        boolean rejected(String field) {
            String prefix = field + ":";
            return rejected.stream().anyMatch(value -> value.startsWith(prefix));
        }

        List<String> applied() {
            return List.copyOf(applied);
        }

        List<String> rejections() {
            return List.copyOf(rejected);
        }
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceParseSkipped("int", new NumberFormatException("non-finite"));
                return null;
            }
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException ignored) {
            traceParseSkipped("int", ignored);
            return null;
        }
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceParseSkipped("long", new NumberFormatException("non-finite"));
                return null;
            }
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException ignored) {
            traceParseSkipped("long", ignored);
            return null;
        }
    }

    private static void traceParseSkipped(String stage, Throwable error) {
        LOG.log(System.Logger.Level.DEBUG,
                "PlanHintApplier parse skipped stage="
                        + SafeRedactor.traceLabelOrFallback(stage, "unknown")
                        + " errorType="
                        + errorType(error));
    }

    private static String errorType(Throwable error) {
        if (error instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(
                error == null ? null : error.getClass().getSimpleName(),
                "unknown");
    }

    private static Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n")) return false;
        return null;
    }

    private static List<String> asStrList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object v : list) {
                String s = asString(v);
                if (s != null) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private static List<Integer> asIntList(Object o) {
        if (o instanceof List<?> list) {
            List<Integer> out = new ArrayList<>();
            for (Object v : list) {
                Integer i = asInt(v);
                if (i != null) out.add(i);
            }
            return out;
        }
        return List.of();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static Integer firstNonNullInt(Integer... vals) {
        if (vals == null) return null;
        for (Integer v : vals) if (v != null) return v;
        return null;
    }

    private static Long firstNonNullLong(Long... vals) {
        if (vals == null) return null;
        for (Long v : vals) if (v != null) return v;
        return null;
    }

    private static Boolean firstNonNullBool(Boolean... vals) {
        if (vals == null) return null;
        for (Boolean v : vals) if (v != null) return v;
        return null;
    }

    @SafeVarargs
    private static List<String> firstNonEmptyStrList(List<String>... lists) {
        if (lists == null) return List.of();
        for (List<String> l : lists) if (l != null && !l.isEmpty()) return l;
        return List.of();
    }

    @SafeVarargs
    private static List<Integer> firstNonEmptyIntList(List<Integer>... lists) {
        if (lists == null) return List.of();
        for (List<Integer> l : lists) if (l != null && !l.isEmpty()) return l;
        return List.of();
    }
}
