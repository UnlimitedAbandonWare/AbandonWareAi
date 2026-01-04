package com.example.lms.moe;

import com.example.lms.guard.KeyResolver;
import com.example.lms.infra.resilience.NightmareBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime resource snapshot to help the offline strategy selector.
 */
@Component
public class RgbResourceProbe {

    private static final Logger log = LoggerFactory.getLogger(RgbResourceProbe.class);

    private final NightmareBreaker breaker;
    private final Environment env;
    private final KeyResolver keyResolver;
    private final RgbMoeProperties props;

    private final AtomicLong lastBlueCallAtMs = new AtomicLong(0L);

    public RgbResourceProbe(NightmareBreaker breaker,
            Environment env,
            KeyResolver keyResolver,
            RgbMoeProperties props) {
        this.breaker = breaker;
        this.env = env;
        this.keyResolver = keyResolver;
        this.props = props;
    }

    public Snapshot snapshot() {
        String redBaseUrl = trimToNull(env.getProperty("llm.base-url"));
        String greenBaseUrl = trimToNull(env.getProperty("llm.fast.base-url"));

        boolean redHealthy = redBaseUrl != null;
        boolean greenHealthy = greenBaseUrl != null || redHealthy;

        boolean blueKeyOk;
        try {
            blueKeyOk = hasText(keyResolver.resolveGeminiApiKeyStrict());
        } catch (Exception e) {
            // strict conflict should still be visible in logs
            log.warn("[RGB] gemini key conflict: {}", e.getMessage());
            blueKeyOk = false;
        }

        long now = System.currentTimeMillis();
        long last = lastBlueCallAtMs.get();
        long cooldownSec = Math.max(0, props.getBlueCooldownSeconds());
        boolean cooldownOk = (last <= 0L) || (now - last) >= (cooldownSec * 1000L);

        boolean blueHealthy = props.isBlueEnabled() && blueKeyOk && cooldownOk;

        List<String> openKeys = new ArrayList<>();
        if (breaker != null) {
            Map<String, NightmareBreaker.StateView> snap = breaker.snapshot();
            for (Map.Entry<String, NightmareBreaker.StateView> e : snap.entrySet()) {
                NightmareBreaker.StateView sv = e.getValue();
                if (sv != null && sv.open) {
                    openKeys.add(e.getKey());
                }
            }
        }

        return new Snapshot(redHealthy, greenHealthy, blueHealthy, openKeys, redBaseUrl, greenBaseUrl,
                cooldownOk ? 0L : Math.max(0L, (cooldownSec * 1000L) - (now - last)));
    }

    public void markBlueCalled() {
        lastBlueCallAtMs.set(System.currentTimeMillis());
    }

    private static boolean hasText(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String trimToNull(String v) {
        if (v == null)
            return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public record Snapshot(
            boolean redHealthy,
            boolean greenHealthy,
            boolean blueHealthy,
            List<String> breakerOpenKeys,
            String redBaseUrl,
            String greenBaseUrl,
            long blueCooldownRemainingMs) {
    }
}
