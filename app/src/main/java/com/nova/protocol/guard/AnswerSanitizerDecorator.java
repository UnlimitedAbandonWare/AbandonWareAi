package com.nova.protocol.guard;

import com.nova.protocol.context.RuleBreakContext;
import reactor.util.context.ContextView;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.guard.AnswerSanitizerDecorator
 * Role: config
 * Dependencies: com.nova.protocol.context.RuleBreakContext
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.guard.AnswerSanitizerDecorator
role: config
*/
public class AnswerSanitizerDecorator {

    private final PIISanitizer piiSanitizer;

    public AnswerSanitizerDecorator(PIISanitizer piiSanitizer) {
        this.piiSanitizer = piiSanitizer;
    }

    public String apply(String output, ContextView ctx) {
        String out = piiSanitizer.scrub(output);
        if (RuleBreakContext.isActive(ctx)) {
            out = "【주의: 확장 탐색 모드 적용됨】\n" + out;
        }
        return out;
    }
}