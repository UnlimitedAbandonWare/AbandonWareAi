package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "router.moe")
public class MoeRoutingProps {

    private int tokensThreshold = 280;
    private double complexityThreshold = 0.55;
    private double uncertaintyThreshold = 0.35;
    private double webEvidenceThreshold = 0.55;
    private boolean escalateOnRigidTemp = true;

    public int getTokensThreshold() { return tokensThreshold; }
    public void setTokensThreshold(int tokensThreshold) { this.tokensThreshold = tokensThreshold; }

    public double getComplexityThreshold() { return complexityThreshold; }
    public void setComplexityThreshold(double complexityThreshold) { this.complexityThreshold = complexityThreshold; }

    public double getUncertaintyThreshold() { return uncertaintyThreshold; }
    public void setUncertaintyThreshold(double uncertaintyThreshold) { this.uncertaintyThreshold = uncertaintyThreshold; }

    public double getWebEvidenceThreshold() { return webEvidenceThreshold; }
    public void setWebEvidenceThreshold(double webEvidenceThreshold) { this.webEvidenceThreshold = webEvidenceThreshold; }

    public boolean isEscalateOnRigidTemp() { return escalateOnRigidTemp; }
    public void setEscalateOnRigidTemp(boolean escalateOnRigidTemp) { this.escalateOnRigidTemp = escalateOnRigidTemp; }
}
