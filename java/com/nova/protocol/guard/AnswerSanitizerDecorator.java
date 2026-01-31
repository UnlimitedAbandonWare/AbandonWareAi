package com.nova.protocol.guard;

import com.nova.protocol.context.RuleBreakContext;
import reactor.util.context.ContextView;



/**
 * 기존 AnswerSanitizer 앞단 혹은 후단에서 호출하는 래퍼 예시.
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