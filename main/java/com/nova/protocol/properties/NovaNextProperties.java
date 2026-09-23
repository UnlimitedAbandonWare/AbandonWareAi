package com.nova.protocol.properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nova.next")
public class NovaNextProperties {
    private boolean enabled = true;
    private double alphaCvar = 0.90;
    private double lambdaCvar = 0.35;
    private double p0 = 1.20;
    private double alphaTwpm = 0.80;
    private int kTotal = 24;
    private double tempSoftmax = 0.85;
    private int floorWeb = 4;
    private int floorVec = 4;
    private int floorKg = 2;
    private String defaultPlanId = "hyper_nova.v1";
    private Grandas grandas = new Grandas();

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
    public String getDefaultPlanId() { return defaultPlanId; }
    public void setDefaultPlanId(String defaultPlanId) { this.defaultPlanId = defaultPlanId; }
    public Grandas getGrandas() { return grandas; }
    public void setGrandas(Grandas grandas) { this.grandas = grandas == null ? new Grandas() : grandas; }

    public static class Grandas {
        private double maxAdjustment = 0.18;
        private double pMax = 3.0;
        private double bodeC = 1.6;

        public double getMaxAdjustment() { return maxAdjustment; }
        public void setMaxAdjustment(double maxAdjustment) { this.maxAdjustment = maxAdjustment; }
        public double getPMax() { return pMax; }
        public void setPMax(double pMax) { this.pMax = pMax; }
        public double getBodeC() { return bodeC; }
        public void setBodeC(double bodeC) { this.bodeC = bodeC; }
    }
}
