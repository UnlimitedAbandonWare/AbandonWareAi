package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class PlanHintApplier {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${plans.cache.ttl-ms:5000}")
    private long cacheTtlMs;

    private record CacheEntry(PlanHints hints, long loadedAtMs) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PlanHints load(String planId) {
        String normalized = normalizePlanId(planId);
        CacheEntry hit = cache.get(normalized);
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.loadedAtMs) <= cacheTtlMs) {
            return hit.hints;
        }

        PlanHints loaded = loadInternal(normalized);
        cache.put(normalized, new CacheEntry(loaded, now));
        return loaded;
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

        TraceStore.put("plan.id", ph.planId());
        TraceStore.put("plan.officialOnly", ph.officialSourcesOnly());
        TraceStore.put("plan.minCitations", ph.minCitations());
        TraceStore.put("plan.retrievalOrder", ph.retrievalOrder());
        TraceStore.put("plan.kSchedule", ph.kSchedule());
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

        // keep legacy string hints
        meta.put("allowWeb", String.valueOf(hints.isAllowWeb()));
        meta.put("allowRag", String.valueOf(hints.isAllowRag()));
        meta.put("webTopK", String.valueOf(hints.getWebTopK()));
        meta.put("vecTopK", String.valueOf(hints.getVecTopK()));

        TraceStore.put("plan.meta.applied", true);
    }

    // ---------------- loader ----------------

    private PlanHints loadInternal(String planId) {
        Resource res = findPlanResource(planId);
        if (res == null || !res.exists()) {
            TraceStore.append("plan.load.miss", planId);
            return PlanHints.empty(planId);
        }

        try (InputStream in = res.getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            if (root == null) return PlanHints.empty(planId);

            Map<String, Object> plan = asMap(root.get("plan"));
            Map<String, Object> overrides = asMap(plan.get("overrides"));
            Map<String, Object> props = asMap(overrides.get("properties"));
            Map<String, Object> knobs = asMap(overrides.get("knobs"));
            Map<String, Object> params = asMap(root.get("params"));

            Boolean officialOnly = firstNonNullBool(
                    asBool(deepGet(root, "retrieval.officialSourcesOnly")),
                    asBool(deepGet(root, "officialSourcesOnly")),
                    asBool(deepGet(root, "official_sources_only")),
                    asBool(props.get("officialSourcesOnly")),
                    asBool(props.get("official_sources_only"))
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

            List<Integer> kSchedule = firstNonEmptyIntList(
                    asIntList(deepGet(root, "retrieval.k_schedule")),
                    asIntList(deepGet(root, "k_schedule"))
            );

            Integer webTopK = firstNonNullInt(
                    asInt(deepGet(root, "retrieval.k.web")),
                    asInt(deepGet(root, "retrieval.topk.web")),
                    asInt(deepGet(root, "k_allocation.web")),
                    asInt(deepGet(root, "kAllocation.web")),
                    asInt(props.get("naver.search.web-top-k")),
                    asInt(props.get("web.search.top-k")),
                    asInt(params.get("webTopK"))
            );

            Integer vecTopK = firstNonNullInt(
                    asInt(deepGet(root, "retrieval.k.vector")),
                    asInt(deepGet(root, "retrieval.topk.vector")),
                    asInt(deepGet(root, "k_allocation.vector")),
                    asInt(deepGet(root, "kAllocation.vector")),
                    asInt(props.get("rag.vector.top-k")),
                    asInt(params.get("vecTopK"))
            );

            Integer kgTopK = firstNonNullInt(
                    asInt(deepGet(root, "retrieval.k.kg")),
                    asInt(deepGet(root, "retrieval.topk.kg")),
                    asInt(deepGet(root, "k_allocation.kg")),
                    asInt(deepGet(root, "kAllocation.kg")),
                    asInt(params.get("kgTopK"))
            );

            Long webBudgetMs = firstNonNullLong(
                    asLong(deepGet(root, "budgets.web_ms")),
                    asLong(deepGet(root, "budgets.webMs")),
                    asLong(deepGet(root, "budget.web_ms")),
                    asLong(deepGet(root, "budget.webMs")),
                    asLong(params.get("webBudgetMs"))
            );

            Long vecBudgetMs = firstNonNullLong(
                    asLong(deepGet(root, "budgets.vec_ms")),
                    asLong(deepGet(root, "budgets.vecMs")),
                    asLong(deepGet(root, "budget.vec_ms")),
                    asLong(deepGet(root, "budget.vecMs")),
                    asLong(params.get("vecBudgetMs"))
            );

            Integer minCitations = firstNonNullInt(
                    asInt(deepGet(root, "guards.min_citations")),
                    asInt(deepGet(root, "gates.citationMin")),
                    asInt(deepGet(root, "gates.citation.min")),
                    asInt(deepGet(root, "gates.citation_min")),
                    asInt(props.get("gate.citation.min")),
                    asInt(params.get("minCitations"))
            );

            Boolean allowWeb = firstNonNullBool(asBool(params.get("allowWeb")), asBool(deepGet(root, "allowWeb")));
            Boolean allowRag = firstNonNullBool(
                    asBool(params.get("allowRag")),
                    asBool(params.get("allowVector")),
                    asBool(deepGet(root, "allowRag"))
            );

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
                    asBool(props.get("onnx.enabled"))
            );

            Boolean overdriveEnabled = firstNonNullBool(
                    asBool(deepGet(root, "overdrive")),
                    asBool(deepGet(root, "overdrive.enabled")),
                    asBool(knobs.get("overdrive.enabled")),
                    asBool(props.get("overdrive.enabled"))
            );

            Map<String, Object> raw = new HashMap<>();
            raw.put("resource", res.getFilename());
            raw.put("rootKeys", root.keySet());

            return new PlanHints(
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
                    raw
            );
        } catch (Exception e) {
            TraceStore.append("plan.load.error", planId + ":" + e.getClass().getSimpleName());
            return PlanHints.empty(planId);
        }
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
        if (lower.matches(".*\\.v\\d+$")) return lower;

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
        if (lower.contains("zero")) return "zero_break.v1";
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

    private static String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception ignored) { return null; }
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception ignored) { return null; }
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
