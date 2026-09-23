
package com.abandonware.ai.agent.integrations.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nineTile")
public class NineTileConfig {
    private boolean enabled = true;
    private double minConfidence = 0.62;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double v) { this.minConfidence = v; }
}
