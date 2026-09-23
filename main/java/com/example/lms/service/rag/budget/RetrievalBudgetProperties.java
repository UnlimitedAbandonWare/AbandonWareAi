package com.example.lms.service.rag.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.budget")
public class RetrievalBudgetProperties {
    private boolean enabled = true;
    private int maxQueryBurst = 6;
    private int defaultQueryBurst = 3;
    private double hypernovaRequiresAnchorConfidence = 0.70d;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxQueryBurst() {
        return maxQueryBurst;
    }

    public void setMaxQueryBurst(int maxQueryBurst) {
        this.maxQueryBurst = maxQueryBurst;
    }

    public int getDefaultQueryBurst() {
        return defaultQueryBurst;
    }

    public void setDefaultQueryBurst(int defaultQueryBurst) {
        this.defaultQueryBurst = defaultQueryBurst;
    }

    public double getHypernovaRequiresAnchorConfidence() {
        return hypernovaRequiresAnchorConfidence;
    }

    public void setHypernovaRequiresAnchorConfidence(double hypernovaRequiresAnchorConfidence) {
        this.hypernovaRequiresAnchorConfidence = hypernovaRequiresAnchorConfidence;
    }
}
