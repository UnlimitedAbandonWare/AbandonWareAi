package com.abandonware.ai.rag.planner;

import java.util.Map;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.rag.planner.PlanDefinition
 * Role: config
 * Feature Flags: whitelist, kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.rag.planner.PlanDefinition
role: config
flags: [whitelist, kg]
*/
public class PlanDefinition {
    private String id;
    private String description;
    private int webTopK = 10;
    private int vectorTopK = 8;
    private int kgTopK = 0;
    private boolean whitelistRequired = true;
    private int minCitations = 2;
    private boolean dppEnabled = true;
    private String calibrator = "minmax";
    private boolean mpLawEnabled = true;
    private int onnxMaxConcurrency = 4;
    private long timeBudgetMs = 6000L;
    private Map<String, Object> params;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getWebTopK() { return webTopK; }
    public void setWebTopK(int webTopK) { this.webTopK = webTopK; }
    public int getVectorTopK() { return vectorTopK; }
    public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }
    public int getKgTopK() { return kgTopK; }
    public void setKgTopK(int kgTopK) { this.kgTopK = kgTopK; }
    public boolean isWhitelistRequired() { return whitelistRequired; }
    public void setWhitelistRequired(boolean whitelistRequired) { this.whitelistRequired = whitelistRequired; }
    public int getMinCitations() { return minCitations; }
    public void setMinCitations(int minCitations) { this.minCitations = minCitations; }
    public boolean isDppEnabled() { return dppEnabled; }
    public void setDppEnabled(boolean dppEnabled) { this.dppEnabled = dppEnabled; }
    public String getCalibrator() { return calibrator; }
    public void setCalibrator(String calibrator) { this.calibrator = calibrator; }
    public boolean isMpLawEnabled() { return mpLawEnabled; }
    public void setMpLawEnabled(boolean mpLawEnabled) { this.mpLawEnabled = mpLawEnabled; }
    public int getOnnxMaxConcurrency() { return onnxMaxConcurrency; }
    public void setOnnxMaxConcurrency(int onnxMaxConcurrency) { this.onnxMaxConcurrency = onnxMaxConcurrency; }
    public long getTimeBudgetMs() { return timeBudgetMs; }
    public void setTimeBudgetMs(long timeBudgetMs) { this.timeBudgetMs = timeBudgetMs; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}