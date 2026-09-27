package com.nova.protocol.telemetry;

import com.nova.protocol.context.BraveContext;
import com.nova.protocol.context.RuleBreakContext;
import reactor.util.context.ContextView;
import java.time.Instant;
import java.util.logging.Logger;




public class ModeAuditLogger {
    private static final Logger log = Logger.getLogger("NovaModeAudit");
    private final boolean enabled;

    public ModeAuditLogger(boolean enabled) { this.enabled = enabled; }

    public void log(ContextView ctx, String phase) {
        if (!enabled) return;
        boolean brave = BraveContext.isOn(ctx);
        boolean rb = RuleBreakContext.isActive(ctx);
        log.info("[NOVA-AUDIT] phase=" + phase + " brave=" + brave + " rulebreak=" + rb + " ts=" + Instant.now());
    }
}