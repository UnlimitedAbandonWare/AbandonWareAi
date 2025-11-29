package com.abandonware.ai.agent.zsys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

@Component
public class ZSystem {
    private final boolean fallbackEnabled;
    public ZSystem(@Value("{zsys.fallback.enabled:true}") boolean fallbackEnabled) {
        this.fallbackEnabled = fallbackEnabled;
    }
    public <T> T runWithFallback(Supplier<T> primary, Supplier<T> fallback) {
        try {
            return primary.get();
        } catch (Exception ex) {
            if (fallbackEnabled && fallback != null) {
                return fallback.get();
            }
            throw ex;
        }
    }
}
