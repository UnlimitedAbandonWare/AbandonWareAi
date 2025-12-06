package com.example.lms.guard.rulebreak;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.example.lms.telemetry.SseEventPublisher;



@Component
public class RuleBreakEvents {

    @Autowired(required=false) SseEventPublisher sse;

    public void publish(String type, RuleBreakContext ctx) {
        if (sse == null || ctx == null || !ctx.isValid()) return;
        sse.emit("rulebreak." + type, ctx);
    }
}