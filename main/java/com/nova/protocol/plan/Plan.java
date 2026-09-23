package com.nova.protocol.plan;

import java.util.Map;



public class Plan {
    private String id;
    private int citationMin = 3;
    private Map<String, Integer> kAllocation; // web, vec, kg
    private Map<String, Integer> timeouts;    // webMs, vecMs, kgMs
    private Map<String, Object> burst;        // n, extremeZLow, extremeZHigh
    private boolean enableOverdrive = false;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getCitationMin() { return citationMin; }
    public void setCitationMin(int citationMin) { this.citationMin = citationMin; }
    public Map<String, Integer> getkAllocation() { return kAllocation; }
    public void setkAllocation(Map<String, Integer> kAllocation) { this.kAllocation = kAllocation; }
    public Map<String, Integer> getTimeouts() { return timeouts; }
    public void setTimeouts(Map<String, Integer> timeouts) { this.timeouts = timeouts; }
    public Map<String, Object> getBurst() { return burst; }
    public void setBurst(Map<String, Object> burst) { this.burst = burst; }
    public boolean isEnableOverdrive() { return enableOverdrive; }
    public void setEnableOverdrive(boolean enableOverdrive) { this.enableOverdrive = enableOverdrive; }
}