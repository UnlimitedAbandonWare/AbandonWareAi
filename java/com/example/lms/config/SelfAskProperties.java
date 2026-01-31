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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBiTopN() { return biTopN; }
    public void setBiTopN(int biTopN) { this.biTopN = biTopN; }
    public int getCrossTopN() { return crossTopN; }
    public void setCrossTopN(int crossTopN) { this.crossTopN = crossTopN; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
}