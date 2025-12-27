package com.nova.protocol.telemetry;

import com.nova.protocol.context.BraveContext;
import com.nova.protocol.context.RuleBreakContext;
import reactor.util.context.ContextView;
import java.time.Instant;
import java.util.logging.Logger;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.telemetry.ModeAuditLogger
 * Role: config
 * Feature Flags: telemetry
 * Dependencies: com.nova.protocol.context.BraveContext, com.nova.protocol.context.RuleBreakContext
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.telemetry.ModeAuditLogger
role: config
flags: [telemetry]
*/
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