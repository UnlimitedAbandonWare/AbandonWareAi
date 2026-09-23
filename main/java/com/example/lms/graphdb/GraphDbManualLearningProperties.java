package com.example.lms.graphdb;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "graphdb.manual-learning")
public class GraphDbManualLearningProperties {

    private boolean enabled = false;
    private boolean dryRunDefault = true;
    private boolean vectorEnabled = true;
    private boolean neo4jEnabled = true;
    private boolean brainStateMirrorEnabled = false;
    private int maxTextChars = 50_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRunDefault() {
        return dryRunDefault;
    }

    public void setDryRunDefault(boolean dryRunDefault) {
        this.dryRunDefault = dryRunDefault;
    }

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public void setVectorEnabled(boolean vectorEnabled) {
        this.vectorEnabled = vectorEnabled;
    }

    public boolean isNeo4jEnabled() {
        return neo4jEnabled;
    }

    public void setNeo4jEnabled(boolean neo4jEnabled) {
        this.neo4jEnabled = neo4jEnabled;
    }

    public boolean isBrainStateMirrorEnabled() {
        return brainStateMirrorEnabled;
    }

    public void setBrainStateMirrorEnabled(boolean brainStateMirrorEnabled) {
        this.brainStateMirrorEnabled = brainStateMirrorEnabled;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = Math.max(1_000, Math.min(maxTextChars, 1_000_000));
    }
}
