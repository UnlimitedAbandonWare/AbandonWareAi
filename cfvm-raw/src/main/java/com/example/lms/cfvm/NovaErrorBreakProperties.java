package com.example.lms.cfvm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nova.errorbreak")
public class NovaErrorBreakProperties {
  private boolean enabled = true;
  private double warnThreshold = 0.60;
  private double breakThreshold = 0.80;
  private int cooldownSeconds = 900;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public double getWarnThreshold() { return warnThreshold; }
  public void setWarnThreshold(double warnThreshold) { this.warnThreshold = warnThreshold; }
  public double getBreakThreshold() { return breakThreshold; }
  public void setBreakThreshold(double breakThreshold) { this.breakThreshold = breakThreshold; }
  public int getCooldownSeconds() { return cooldownSeconds; }
  public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
}