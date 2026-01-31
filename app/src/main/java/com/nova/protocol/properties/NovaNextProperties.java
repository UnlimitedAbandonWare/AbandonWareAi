package com.nova.protocol.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nova.next")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.properties.NovaNextProperties
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.nova.protocol.properties.NovaNextProperties
role: config
*/
public class NovaNextProperties {
    private boolean enabled = false;
    private double alphaCvar = 0.90;
    private double lambdaCvar = 0.35;
    private double p0 = 1.20;
    private double alphaTwpm = 0.80;
    private int kTotal = 24;
    private double tempSoftmax = 0.85;
    private int floorWeb = 4, floorVec = 4, floorKg = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getAlphaCvar() { return alphaCvar; }
    public void setAlphaCvar(double alphaCvar) { this.alphaCvar = alphaCvar; }
    public double getLambdaCvar() { return lambdaCvar; }
    public void setLambdaCvar(double lambdaCvar) { this.lambdaCvar = lambdaCvar; }
    public double getP0() { return p0; }
    public void setP0(double p0) { this.p0 = p0; }
    public double getAlphaTwpm() { return alphaTwpm; }
    public void setAlphaTwpm(double alphaTwpm) { this.alphaTwpm = alphaTwpm; }
    public int getKTotal() { return kTotal; }
    public void setKTotal(int kTotal) { this.kTotal = kTotal; }
    public double getTempSoftmax() { return tempSoftmax; }
    public void setTempSoftmax(double tempSoftmax) { this.tempSoftmax = tempSoftmax; }
    public int getFloorWeb() { return floorWeb; }
    public void setFloorWeb(int floorWeb) { this.floorWeb = floorWeb; }
    public int getFloorVec() { return floorVec; }
    public void setFloorVec(int floorVec) { this.floorVec = floorVec; }
    public int getFloorKg() { return floorKg; }
    public void setFloorKg(int floorKg) { this.floorKg = floorKg; }
}