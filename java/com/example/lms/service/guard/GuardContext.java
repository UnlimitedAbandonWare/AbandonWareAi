// src/main/java/com/example/lms/service/guard/GuardContext.java
package com.example.lms.service.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight guard context that can be shared across guard / gate components.
 *
 * This intentionally stays as a very small POJO so that it is easy to construct
 * from different layers (planner, orchestrator, chat service, etc).
 */
public class GuardContext {

    /**
     * Plan identifier, e.g. "safe_autorun.v1", "brave.v1", "zero_break.v1".
     */
    private String planId;

    /**
     * High level mode: SAFE, BRAVE, ZERO_BREAK, RULE_BREAK ...
     */
    private String mode;

    /**
     * Engine name, e.g. HYPERNOVA, GRANDAS, STANDARD.
     */
    private String engine;

    /**
     * Optional fusion score (e.g. GRANDAS / RRF final score) in [0,1].
     */
    private Double fusionScore;

    /**
     * Optional ONNX re-ranker average score in [0,1].
     */
    private Double onnxScore;

    /**
     * When true, only official domains / high-trust sources should be allowed.
     */
    private boolean officialOnly;

    // plan-driven guard knobs
    private Integer minCitations;
    private final Map<String, Object> planOverrides = new ConcurrentHashMap<>();

    /**
     * Whether this query has been classified as high risk (error-break / failure
     * pattern).
     */
    private boolean highRiskQuery;
    // 민감 주제 플래그 (web query/memory/sampling에 관통)
    private volatile boolean sensitiveTopic;
    // --- Jammini view / safety context extensions ---
    // Flag indicating whether the user question is about a person / organisation /
    // entity
    private boolean entityQuery;
    // Memory profile: "MEMORY" (stable, long-term) vs "NONE" (stateless / free
    // view)
    private String memoryProfile;
    // Raw mode propagated from headers or upstream router
    // (safe/brave/zero_break/free/S1/S2)
    private String headerMode;
    // Guard level propagated from headers (low/normal/high, optional)
    private String guardLevel;

    /**
     * Web search primary override.
     *
     * <p>
     * When set (e.g. "NAVER" or "BRAVE"), lower layers such as
     * {@code HybridWebSearchProvider} may prefer this value over the configured
     * application property. This is especially useful for soak/CLI runs where
     * we want deterministic provider split without restarting the process.
     * </p>
     */
    private String webPrimary;

    // (UAW: IrregularityProfiler 연동) 오케스트레이션 불안정 점수 및 사유
    private volatile double irregularityScore = 0.0;
    // May be updated from multiple async tasks (search, RAG, etc.). Use a thread-safe
    // list to avoid cross-thread corruption when GuardContext is propagated.
    private final List<String> irregularityReasons = new CopyOnWriteArrayList<>();

    // ── Orchestration mode flags (STRIKE/COMPRESSION/BYPASS) ─────────────────
    private volatile boolean compressionMode;
    private volatile boolean strikeMode;
    private volatile boolean bypassMode;
    private volatile boolean webRateLimited;
    private volatile String bypassReason;

    // ── Nova overlay: userQuery + aux LLM health flags ─────────────────────
    private volatile String userQuery;

    /**
     * Soft degradation signal: aux LLM is still reachable but unreliable/slow/blank.
     * Triggers STRIKE/COMPRESSION but NOT BYPASS.
     */
    private volatile boolean auxDegraded;

    /**
     * Hard-down signal: breaker-open or repeated hard failures in aux stages.
     * May justify BYPASS decisions.
     */
    private volatile boolean auxHardDown;

    public GuardContext() {
    }

    // -- getters / setters --

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public Double getFusionScore() {
        return fusionScore;
    }

    public void setFusionScore(Double fusionScore) {
        this.fusionScore = fusionScore;
    }

    public Double getOnnxScore() {
        return onnxScore;
    }

    public void setOnnxScore(Double onnxScore) {
        this.onnxScore = onnxScore;
    }

    public boolean isOfficialOnly() {
        return officialOnly;
    }

    public void setOfficialOnly(boolean officialOnly) {
        this.officialOnly = officialOnly;
    }

    public Integer getMinCitations() {
        return minCitations;
    }

    public void setMinCitations(Integer minCitations) {
        this.minCitations = minCitations;
    }

    public Map<String, Object> getPlanOverrides() {
        return planOverrides;
    }

    public void putPlanOverride(String key, Object value) {
        if (key == null || key.isBlank() || value == null)
            return;
        planOverrides.put(key, value);
    }

    public boolean planBool(String key, boolean defaultValue) {
        Object v = planOverrides.get(key);
        if (v instanceof Boolean b)
            return b;
        if (v == null)
            return defaultValue;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s))
            return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s))
            return false;
        return defaultValue;
    }

    public Object getPlanOverride(String key) {
        if (key == null || key.isBlank()) return null;
        return planOverrides.get(key);
    }

    /**
     * Fail-soft numeric plan override getter.
     * <p>
     * Accepts: Number, numeric string ("2"), boolean strings ("true"/"false") → 1/0.
     */
    public int planInt(String key, int defaultValue) {
        Object v = getPlanOverride(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        String s = String.valueOf(v).trim();
        if (s.isBlank()) return defaultValue;
        String lower = s.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "yes".equals(lower) || "y".equals(lower)) return 1;
        if ("false".equals(lower) || "no".equals(lower) || "n".equals(lower)) return 0;
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            try {
                return (int) Long.parseLong(s);
            } catch (Exception ignored2) {
                return defaultValue;
            }
        }
    }

    /**
     * Fail-soft numeric plan override getter.
     * <p>
     * Accepts: Number, numeric string ("1500"), boolean strings ("true"/"false") → 1/0.
     */
    public long planLong(String key, long defaultValue) {
        Object v = getPlanOverride(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1L : 0L;
        String s = String.valueOf(v).trim();
        if (s.isBlank()) return defaultValue;
        String lower = s.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "yes".equals(lower) || "y".equals(lower)) return 1L;
        if ("false".equals(lower) || "no".equals(lower) || "n".equals(lower)) return 0L;
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }


public boolean isSensitiveTopic() {
    return sensitiveTopic;
}

public void setSensitiveTopic(boolean sensitiveTopic) {
    this.sensitiveTopic = sensitiveTopic;
}

/**
 * Fail-soft numeric plan override getter (double).
 * <p>
 * Accepts: Number, numeric string ("0.7"), boolean strings ("true"/"false") → 1/0.
 */
public double planDouble(String key, double defaultValue) {
    Object v = getPlanOverride(key);
    if (v == null) return defaultValue;
    if (v instanceof Number n) return n.doubleValue();
    if (v instanceof Boolean b) return b ? 1.0d : 0.0d;
    String s = String.valueOf(v).trim();
    if (s.isBlank()) return defaultValue;
    String lower = s.toLowerCase(Locale.ROOT);
    if ("true".equals(lower) || "yes".equals(lower) || "y".equals(lower)) return 1.0d;
    if ("false".equals(lower) || "no".equals(lower) || "n".equals(lower)) return 0.0d;
    try {
        return Double.parseDouble(s);
    } catch (Exception ignored) {
        return defaultValue;
    }
}

public Double planDouble(String key) {
    Object v = getPlanOverride(key);
    if (v == null) return null;
    double d = planDouble(key, Double.NaN);
    return Double.isNaN(d) ? null : d;
}

    public boolean isHighRiskQuery() {
        return highRiskQuery;
    }

    public void setHighRiskQuery(boolean highRiskQuery) {
        this.highRiskQuery = highRiskQuery;
    }

    // --- Extended getters / setters for Jammini view metadata ---
    public boolean isEntityQuery() {
        return entityQuery;
    }

    public void setEntityQuery(boolean entityQuery) {
        this.entityQuery = entityQuery;
    }

    public String getMemoryProfile() {
        return memoryProfile;
    }

    public void setMemoryProfile(String memoryProfile) {
        this.memoryProfile = memoryProfile;
    }

    public String getHeaderMode() {
        return headerMode;
    }

    public void setHeaderMode(String headerMode) {
        this.headerMode = headerMode;
    }

    public String getGuardLevel() {
        return guardLevel;
    }

    public void setGuardLevel(String guardLevel) {
        this.guardLevel = guardLevel;
    }

    // Domain profile for plan-driven hints
    private String domainProfile;

    public String getDomainProfile() {
        return domainProfile;
    }

    public void setDomainProfile(String domainProfile) {
        this.domainProfile = domainProfile;
    }

    public String getWebPrimary() {
        return webPrimary;
    }

    public void setWebPrimary(String webPrimary) {
        this.webPrimary = webPrimary;
    }

    public boolean isCompressionMode() {
        return compressionMode;
    }

    public void setCompressionMode(boolean compressionMode) {
        this.compressionMode = compressionMode;
    }

    public boolean isStrikeMode() {
        return strikeMode;
    }

    public void setStrikeMode(boolean strikeMode) {
        this.strikeMode = strikeMode;
    }

    public boolean isBypassMode() {
        return bypassMode;
    }

    public void setBypassMode(boolean bypassMode) {
        this.bypassMode = bypassMode;
    }

    public boolean isWebRateLimited() {
        return webRateLimited;
    }

    public void setWebRateLimited(boolean webRateLimited) {
        this.webRateLimited = webRateLimited;
    }

    public String getBypassReason() {
        return bypassReason;
    }

    public void setBypassReason(String bypassReason) {
        this.bypassReason = bypassReason;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public boolean isAuxDegraded() {
        return auxDegraded;
    }

    public void setAuxDegraded(boolean auxDegraded) {
        this.auxDegraded = auxDegraded;
    }

    public boolean isAuxHardDown() {
        return auxHardDown;
    }

    public void setAuxHardDown(boolean auxHardDown) {
        this.auxHardDown = auxHardDown;
    }

    /** Backward compatible: soft OR hard 어느 쪽이든 true면 aux가 불안정 */
    public boolean isAuxDown() {
        return auxDegraded || auxHardDown;
    }

    /** @deprecated Use setAuxDegraded() or setAuxHardDown() explicitly */
    @Deprecated
    public void setAuxDown(boolean auxDown) {
        // Backward compatibility: treat setAuxDown(true) as soft degradation.
        this.auxDegraded = auxDown;
    }

    /**
     * Lightweight heuristic to detect whether a query is about a person or
     * organisation
     * (e.g. 교수, 병원, 대학교, 약력 등).
     */
    public static boolean detectEntityQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        return lower.matches(".*(교수|교수님|의사|원장|대표|사장|회장|병원|의료원|대학교|대학|학과|연구실|연구소|센터|학교|프로필|약력|경력|이력|인물).*");
    }

    /**
     * Convenience helper to populate {@link #entityQuery} from a raw
     * natural-language question.
     */
    public void setEntityQueryFromQuestion(String question) {
        this.entityQuery = detectEntityQuery(question);
    }

    /**
     * Whether this context should be treated as "aggressive" (S2 / brave / free
     * view).
     */
    public boolean isAggressivePlan() {
        if (headerMode != null) {
            String m = headerMode.toLowerCase(java.util.Locale.ROOT);
            if ("brave".equals(m) || "zero_break".equals(m) || "free".equals(m) || "s2".equals(m)) {
                return true;
            }
        }
        if ("NONE".equalsIgnoreCase(memoryProfile)) {
            return true;
        }
        if (mode != null) {
            String m = mode.toUpperCase(java.util.Locale.ROOT);
            if ("BRAVE".equals(m) || "ZERO_BREAK".equals(m) || "WILD".equals(m) || "FREE".equals(m)) {
                return true;
            }
        }
        if (planId != null) {
            String p = planId.toLowerCase(java.util.Locale.ROOT);
            if (p.contains("brave") || p.contains("zero_break") || p.contains("wild") || p.contains("free")) {
                return true;
            }
        }
        return false;
    }

    /** EvidenceAwareGuard.looksWeak()에서 쓰는 profile() 호환 메서드 */
    public String profile() {
        if (guardLevel != null && !guardLevel.isBlank())
            return guardLevel;
        if (mode != null && !mode.isBlank())
            return mode;
        if (planId != null && !planId.isBlank())
            return planId;
        return "BALANCED";
    }

    /** EvidenceAwareGuard.looksWeak()에서 쓰는 fusionScore() 호환 메서드 */
    public double fusionScore() {
        return (fusionScore != null ? fusionScore : 0.0);
    }

    public synchronized void bumpIrregularity(double delta, String reason) {
        if (delta <= 0)
            return;
        irregularityScore = Math.min(1.0, Math.max(0.0, irregularityScore + delta));
        if (reason != null && !reason.isBlank())
            irregularityReasons.add(reason);
    }

    public double getIrregularityScore() {
        return irregularityScore;
    }

    public void setIrregularityScore(double irregularityScore) {
        this.irregularityScore = Math.min(1.0, Math.max(0.0, irregularityScore));
    }

    public List<String> getIrregularityReasons() {
        return Collections.unmodifiableList(irregularityReasons);
    }

    
    /**
     * Create a shallow copy of this {@link GuardContext}.
     *
     * <p>
     * This is primarily used for fail-soft fallback scopes where we need to
     * temporarily relax a couple of flags (ex: {@code officialOnly}) without
     * mutating the shared context instance that might be observed by other
     * components/threads.
     */
    public GuardContext copy() {
        GuardContext c = new GuardContext();
        c.planId = this.planId;
        c.mode = this.mode;
        c.engine = this.engine;
        c.fusionScore = this.fusionScore;
        c.onnxScore = this.onnxScore;
        c.officialOnly = this.officialOnly;
        c.minCitations = this.minCitations;

        try {
            c.planOverrides.putAll(this.planOverrides);
        } catch (Throwable ignore) {
            // fail-soft
        }

        c.highRiskQuery = this.highRiskQuery;
        c.sensitiveTopic = this.sensitiveTopic;
        c.entityQuery = this.entityQuery;
        c.memoryProfile = this.memoryProfile;
        c.headerMode = this.headerMode;
        c.guardLevel = this.guardLevel;
        c.webPrimary = this.webPrimary;
        c.irregularityScore = this.irregularityScore;
        try {
            c.irregularityReasons.addAll(this.irregularityReasons);
        } catch (Throwable ignore) {
            // fail-soft
        }
        c.compressionMode = this.compressionMode;
        c.strikeMode = this.strikeMode;
        c.bypassMode = this.bypassMode;
        c.webRateLimited = this.webRateLimited;
        c.bypassReason = this.bypassReason;
        c.userQuery = this.userQuery;
        c.auxDegraded = this.auxDegraded;
        c.auxHardDown = this.auxHardDown;
        c.domainProfile = this.domainProfile;
        return c;
    }
/**
     * Create a default, S1-style safe context.
     */
    public static GuardContext defaultContext() {
        GuardContext ctx = new GuardContext();
        ctx.planId = "safe";
        ctx.mode = "safe";
        ctx.engine = "default";
        ctx.fusionScore = 0.0;
        ctx.onnxScore = 0.0;
        ctx.officialOnly = false;
        ctx.highRiskQuery = false;
        ctx.entityQuery = false;
        ctx.memoryProfile = "MEMORY";
        ctx.headerMode = "S1";
        ctx.guardLevel = "BALANCED";
        ctx.webPrimary = null;
        ctx.irregularityScore = 0.0;
        ctx.compressionMode = false;
        ctx.strikeMode = false;
        ctx.bypassMode = false;
        ctx.webRateLimited = false;
        ctx.bypassReason = null;
        return ctx;
    }
}