package com.example.lms.nova;

import com.example.lms.telemetry.SseEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.LinkedHashMap;

@Component
public class NovaTelemetry {

    @Autowired(required = false)
    private SseEventPublisher sse;

    public void publishModeAndBurst(int burstCount) {
        if (sse == null) return;
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("mode.brave", NovaRequestContext.isBrave());
        payload.put("mode.rulebreak", NovaRequestContext.hasRuleBreak());
        payload.put("burst.count", burstCount);
        sse.emit("nova", payload);
    }
}