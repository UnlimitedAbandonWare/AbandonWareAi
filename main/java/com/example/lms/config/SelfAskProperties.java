package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "selfask")
public class SelfAskProperties {
    private boolean enabled = false;
    private int biTopN = 80;
    private int crossTopN = 24;
    private double temperature = 0.4;
    private ThreeWay threeWay = new ThreeWay();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBiTopN() { return biTopN; }
    public void setBiTopN(int biTopN) { this.biTopN = biTopN; }
    public int getCrossTopN() { return crossTopN; }
    public void setCrossTopN(int crossTopN) { this.crossTopN = crossTopN; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public ThreeWay getThreeWay() { return threeWay; }
    public void setThreeWay(ThreeWay threeWay) { this.threeWay = threeWay == null ? new ThreeWay() : threeWay; }

    public static class ThreeWay {
        private Lane bq = new Lane("llmrouter.gemma", "local", 0L, 0.2d);
        private Lane er = new Lane("llmrouter.light", "local", 0L, 0.2d);
        private Lane rc = new Lane("llmrouter.api3", "api3", 0L, 0.2d);

        public Lane getBq() { return bq; }
        public void setBq(Lane bq) { this.bq = bq == null ? new Lane("llmrouter.gemma", "local", 0L, 0.2d) : bq; }
        public Lane getEr() { return er; }
        public void setEr(Lane er) { this.er = er == null ? new Lane("llmrouter.light", "local", 0L, 0.2d) : er; }
        public Lane getRc() { return rc; }
        public void setRc(Lane rc) { this.rc = rc == null ? new Lane("llmrouter.api3", "api3", 0L, 0.2d) : rc; }
    }

    public static class Lane {
        private String model;
        private String provider;
        private long timeoutMs;
        private double temperature;

        public Lane() {
        }

        public Lane(String model, String provider, long timeoutMs, double temperature) {
            this.model = model;
            this.provider = provider;
            this.timeoutMs = timeoutMs;
            this.temperature = temperature;
        }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }
}
