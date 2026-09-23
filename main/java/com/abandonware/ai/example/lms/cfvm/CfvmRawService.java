package com.abandonware.ai.example.lms.cfvm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;




@Service
@RequiredArgsConstructor
public class CfvmRawService {

    private final CfvmRawProperties props;
    private Cache<String, RawMatrixBuffer> store;

    @PostConstruct
    void init() {
        store = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
    }

    public RawMatrixBuffer buffer(String sessionId) {
        return store.get(sessionId, sid -> new RawMatrixBuffer(props.getMaxSlots()));
    }

    public void push(RawSlot slot) {
        if (!props.isEnabled()) return;
        buffer(slot.sessionId()).add(slot);
    }

    public double[] weights(String sessionId) {
        return buffer(sessionId).boltzmann(props.getTemperature());
    }

    /** Utility: get session id from SLF4J MDC (TraceFilter populates it). */
    public static String currentSessionIdOr(String fallback) {
        String sid = MDC.get("sid");
        return sid != null ? sid : fallback;
    }
}