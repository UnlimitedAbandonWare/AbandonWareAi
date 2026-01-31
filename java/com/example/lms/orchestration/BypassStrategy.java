package com.example.lms.orchestration;

import com.example.lms.service.guard.GuardContext;

/**
 * UAW: Centralized bypass/degrade strategy decision.
 *
 * <p>Priority:
 * <ol>
 *   <li>Explicit BYPASS → bypass blade</li>
 *   <li>auxHardDown → bypass blade</li>
 *   <li>STRIKE/COMPRESSION or auxDegraded → strike blade</li>
 *   <li>otherwise → normal blade</li>
 * </ol>
 */
public final class BypassStrategy {
    private BypassStrategy() {
    }

    public static BladeSpec decide(OrchestrationSignals sig, GuardContext ctx) {
        // 1) Explicit bypass mode
        if (sig != null && sig.bypassMode()) {
            return BladeSpec.bypass();
        }

        // 2) Hard-down: breaker-open or hard failure
        if (sig != null && sig.auxHardDown()) {
            return BladeSpec.bypass();
        }
        if (ctx != null && ctx.isAuxHardDown()) {
            return BladeSpec.bypass();
        }

        // 3) Soft degrade: reduce features but do NOT force BYPASS
        if (sig != null && (sig.strikeMode() || sig.compressionMode() || sig.auxDegraded())) {
            return BladeSpec.strike();
        }
        if (ctx != null && (ctx.isAuxDegraded() || ctx.isStrikeMode() || ctx.isCompressionMode())) {
            return BladeSpec.strike();
        }

        // 4) Normal operation
        return BladeSpec.normal();
    }
}
