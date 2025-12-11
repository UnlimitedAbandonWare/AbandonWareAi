// src/main/java/com/example/lms/service/guard/GuardContext.java
package com.example.lms.service.guard;

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

    /**
     * Whether this query has been classified as high risk (error-break / failure pattern).
     */
    private boolean highRiskQuery;
    // --- Jammini view / safety context extensions ---
    // Flag indicating whether the user question is about a person / organisation / entity
    private boolean entityQuery;
    // Memory profile: "MEMORY" (stable, long-term) vs "NONE" (stateless / free view)
    private String memoryProfile;
    // Raw mode propagated from headers or upstream router (safe/brave/zero_break/free/S1/S2)
    private String headerMode;
    // Guard level propagated from headers (low/normal/high, optional)
    private String guardLevel;

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

    /**
     * Lightweight heuristic to detect whether a query is about a person or organisation
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
     * Convenience helper to populate {@link #entityQuery} from a raw natural-language question.
     */
    public void setEntityQueryFromQuestion(String question) {
        this.entityQuery = detectEntityQuery(question);
    }

    /**
     * Whether this context should be treated as "aggressive" (S2 / brave / free view).
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

    /**
     * Create a default, S1-style safe context.
     */
    public static GuardContext defaultContext() {
        GuardContext ctx = new GuardContext();
        ctx.setMemoryProfile("MEMORY");
        return ctx;
    }
}