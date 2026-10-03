package com.example.lms.assist;

import org.springframework.core.env.Environment;
import java.util.Locale;
import java.util.Set;

/** Per-surface settings; missing settings preserve the legacy policy. */
public final class JevSurfacePolicy {
    public record EffectivePolicy(String mode, int decisionWaitMs, int requestTimeoutMs) {}
    private final Environment env;
    public JevSurfacePolicy(Environment env) { this.env=env; }
    public EffectivePolicy resolve(String surface) {
        String global=mode(env.getProperty("demo.jev.mode","off"));
        boolean known=Set.of("focus","cue","main").contains(surface==null?"":surface);
        String override=known?env.getProperty("demo.jev.surface."+surface+".mode"):null;
        String effective="off".equals(global)?"off":override==null?global:mode(override);
        int wait=integer("demo.jev.decision-wait-ms",150,0,5000);
        if(known)wait=integer("demo.jev.surface."+surface+".decision-wait-ms",wait,0,5000);
        return new EffectivePolicy(effective,wait,integer("demo.jev.request-timeout-ms",800,50,10000));
    }
    private static String mode(String value) {
        String normalized=value==null?"off":value.trim().toLowerCase(Locale.ROOT);
        return "on".equals(normalized)||"shadow".equals(normalized)?normalized:"off";
    }
    private int integer(String key,int fallback,int min,int max) {
        try {
            String raw=env.getProperty(key);
            return raw==null?fallback:Math.max(min,Math.min(max,Integer.parseInt(raw.trim())));
        } catch(RuntimeException malformed) { return fallback; }
    }
}
