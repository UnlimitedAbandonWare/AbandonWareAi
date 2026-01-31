package com.nova.protocol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;



@ConfigurationProperties(prefix = "nova")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.config.NovaProperties
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.nova.protocol.config.NovaProperties
role: config
*/
public class NovaProperties {
    private boolean planEnabled = true;
    private boolean braveEnabled = true;
    private boolean rulebreakEnabled = false;
    private String rulebreakHmacSecret = "change-me";
    private long rulebreakTokenTtlSeconds = 3600;
    private String defaultPlanId = "safe_autorun.v1";
    private int citationMin = 3;
    private boolean modeAuditEnabled = true;

    public boolean isPlanEnabled() { return planEnabled; }
    public void setPlanEnabled(boolean planEnabled) { this.planEnabled = planEnabled; }

    public boolean isBraveEnabled() { return braveEnabled; }
    public void setBraveEnabled(boolean braveEnabled) { this.braveEnabled = braveEnabled; }

    public boolean isRulebreakEnabled() { return rulebreakEnabled; }
    public void setRulebreakEnabled(boolean rulebreakEnabled) { this.rulebreakEnabled = rulebreakEnabled; }

    public String getRulebreakHmacSecret() { return rulebreakHmacSecret; }
    public void setRulebreakHmacSecret(String rulebreakHmacSecret) { this.rulebreakHmacSecret = rulebreakHmacSecret; }

    public long getRulebreakTokenTtlSeconds() { return rulebreakTokenTtlSeconds; }
    public void setRulebreakTokenTtlSeconds(long rulebreakTokenTtlSeconds) { this.rulebreakTokenTtlSeconds = rulebreakTokenTtlSeconds; }

    public String getDefaultPlanId() { return defaultPlanId; }
    public void setDefaultPlanId(String defaultPlanId) { this.defaultPlanId = defaultPlanId; }

    public int getCitationMin() { return citationMin; }
    public void setCitationMin(int citationMin) { this.citationMin = citationMin; }

    public boolean isModeAuditEnabled() { return modeAuditEnabled; }
    public void setModeAuditEnabled(boolean modeAuditEnabled) { this.modeAuditEnabled = modeAuditEnabled; }
}