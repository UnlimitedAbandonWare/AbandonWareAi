package com.example.lms.guard;

import com.example.lms.trace.TraceContext;
import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.guard.AnswerSanitizer
 * Role: config
 * Dependencies: com.example.lms.trace.TraceContext
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.guard.AnswerSanitizer
role: config
*/
public class AnswerSanitizer {
    public String sanitize(String answer) {
        if (TraceContext.current().isRuleBreak()) {
            return "[주의: 확장 탐색 모드 적용] " + answer;
        }
        return answer;
    }
}