package com.abandonware.ai.agent.nn;

import org.springframework.boot.context.properties.ConfigurationProperties;



@ConfigurationProperties(prefix = "diag.nn.gradient")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.nn.DiagGradientProperties
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.nn.DiagGradientProperties
role: config
*/
public class DiagGradientProperties {
    private boolean enabled = false;
    private double vanishThreshold = 1e-3;
    private double alpha = 4.0;
    private double beta = 0.0;
    private double eps = 1e-12;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getVanishThreshold() { return vanishThreshold; }
    public void setVanishThreshold(double vanishThreshold) { this.vanishThreshold = vanishThreshold; }

    public double getAlpha() { return alpha; }
    public void setAlpha(double alpha) { this.alpha = alpha; }

    public double getBeta() { return beta; }
    public void setBeta(double beta) { this.beta = beta; }

    public double getEps() { return eps; }
    public void setEps(double eps) { this.eps = eps; }
}