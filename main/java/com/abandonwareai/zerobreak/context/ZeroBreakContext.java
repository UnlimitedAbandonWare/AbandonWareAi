package com.abandonwareai.zerobreak.context;

import java.io.Serializable;
import java.util.*;

/** Request-scoped context for Zero Break / Brave / Safe plans. */
public class ZeroBreakContext implements Serializable {
    private boolean zeroBreakEnabled;
    private String planId; // safe_autorun.v1 | brave.v1 | zero_break.v1
    private List<String> policies = new ArrayList<>(); // recency|max_recall|/* ... */
    private boolean whitelistOverride;
    private String bannerText;
    private Map<String, Object> attributes = new HashMap<>();

    public boolean isZeroBreakEnabled() { return zeroBreakEnabled; }
    public void setZeroBreakEnabled(boolean b) { this.zeroBreakEnabled = b; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public List<String> getPolicies() { return policies; }
    public void setPolicies(List<String> policies) { this.policies = policies; }
    public boolean isWhitelistOverride() { return whitelistOverride; }
    public void setWhitelistOverride(boolean whitelistOverride) { this.whitelistOverride = whitelistOverride; }
    public String getBannerText() { return bannerText; }
    public void setBannerText(String bannerText) { this.bannerText = bannerText; }
    public Map<String,Object> getAttributes() { return attributes; }

    public ZeroBreakContext withAttr(String k, Object v) { this.attributes.put(k, v); return this; }

    @Override public String toString()     {
        return "ZeroBreakContext{" +
                "planId='" + planId + '\'' +
                ", whitelistOverride=" + whitelistOverride +
                '}';
    }
}