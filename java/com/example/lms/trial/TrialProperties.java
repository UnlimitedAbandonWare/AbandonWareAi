
        package com.example.lms.trial;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;




/**
 * Configuration properties controlling the anonymous trial quota system.
 *
 * <p>The trial subsystem allows non-authenticated users to interact with
 * the chat API a limited number of times within a rolling window. The
 * properties defined here control whether the feature is enabled,
 * the maximum number of allowed requests, the window duration, the
 * cookie name used to track the trial identifier, and the secret used
 * to sign the cookie. Bindings are loaded from {@code trial.*} in
 * application.yml or application.properties.</p>
 */
@ConfigurationProperties(prefix = "trial")
public class TrialProperties {

    /** Whether the trial feature is active. When {@code false} the
     * interceptor is disabled and all requests are allowed. */
    private boolean enabled = true;

    /** Maximum number of requests per rolling window. */
    private int limit = 3;

    /** Total daily request limit for a user. */
    private int dailyQuota = 0;

    /** Request limit per user session. */
    private int perSessionQuota = 0;

    /** Duration of the rolling window. Defaults to 24 hours. */
    private Duration window = Duration.ofHours(24);

    /** Name of the cookie that carries the signed trial identifier. */
    private String cookieName = "trial_id";

    /** Secret used to sign and verify the trial cookie. It should be at
     * least 32 bytes long. The default empty string will disable
     * signature checks resulting in a new trial ID for every request. */
    private String cookieSecret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(int dailyQuota) {
        this.dailyQuota = dailyQuota;
    }

    public int getPerSessionQuota() {
        return perSessionQuota;
    }

    public void setPerSessionQuota(int perSessionQuota) {
        this.perSessionQuota = perSessionQuota;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public String getCookieSecret() {
        return cookieSecret;
    }

    public void setCookieSecret(String cookieSecret) {
        this.cookieSecret = cookieSecret;
    }
}