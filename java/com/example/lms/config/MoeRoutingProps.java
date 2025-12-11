package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;




/**
 * Configuration properties governing the router escalation thresholds.  This
 * imperative class provides sensible default values when the corresponding
 * properties are absent.  Properties are bound from {@code router.moe.*} in
 * either application.yml or application.properties.  Each threshold is
 * independent and may be tuned without recompilation.
 */

@ConfigurationProperties(prefix = "router.moe")
public class MoeRoutingProps {

    /**
     * Minimum number of tokens required to trigger a promotion to the MOE
     * model.  Defaults to 1200 when unspecified.
     */
    // Set a lower default token threshold.  When the requested token count exceeds
    // this value the router will promote to the high-tier model.  The default is
    // 280, tuned to allow more general queries to trigger MOE escalation.
    private int tokensThreshold = 280;

    /**
     * Complexity score threshold.  Complexity values in the range [0,1]
     * exceeding this threshold will cause an upgrade.  Defaults to 0.55.
     */
    private double complexityThreshold = 0.55;

    /**
     * Uncertainty score threshold.  Values in the range [0,1] exceeding this
     * threshold will cause an upgrade.  Defaults to 0.40.
     */
    // Lower uncertainty threshold: when the uncertainty score exceeds 0.35 an
    // upgrade is triggered.  This increases the likelihood of escalation on
    // ambiguous or low-confidence inputs.
    private double uncertaintyThreshold = 0.35;

    /**
     * Web evidence score threshold.  Values in the range [0,1] exceeding this
     * threshold will cause an upgrade.  Defaults to 0.60.
     */
    // Lower web evidence threshold: when the fused web evidence score exceeds
    // 0.55 an upgrade is triggered.  This encourages MOE promotion when
    // credible web evidence is abundant.
    private double webEvidenceThreshold = 0.55;

    public int getTokensThreshold() {
        return tokensThreshold;
    }

    public void setTokensThreshold(int tokensThreshold) {
        this.tokensThreshold = tokensThreshold;
    }

    public double getComplexityThreshold() {
        return complexityThreshold;
    }

    public void setComplexityThreshold(double complexityThreshold) {
        this.complexityThreshold = complexityThreshold;
    }

    public double getUncertaintyThreshold() {
        return uncertaintyThreshold;
    }

    public void setUncertaintyThreshold(double uncertaintyThreshold) {
        this.uncertaintyThreshold = uncertaintyThreshold;
    }

    public double getWebEvidenceThreshold() {
        return webEvidenceThreshold;
    }

    public void setWebEvidenceThreshold(double webEvidenceThreshold) {
        this.webEvidenceThreshold = webEvidenceThreshold;
    }
    /** rigid-temp 모델이 비기본 temperature를 못 받으면 MOE로 자동 승격할지 */
    private boolean escalateOnRigidTemp = true;
    public boolean isEscalateOnRigidTemp() { return escalateOnRigidTemp; }
    public void setEscalateOnRigidTemp(boolean escalateOnRigidTemp) { this.escalateOnRigidTemp = escalateOnRigidTemp; }
}