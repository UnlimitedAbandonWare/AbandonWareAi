package com.example.lms.nova;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.guard.rulebreak.RuleBreakEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBreakInterceptorTest {

    @AfterEach
    void cleanup() {
        NovaRequestContext.clearRuleBreak();
        NovaRequestContext.setBrave(false);
    }

    @Test
    void randomTokenDoesNotActivateRuleBreak() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RuleBreakInterceptor.HDR, "wide-random-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuleBreakInterceptor interceptor = new RuleBreakInterceptor(evaluator("expected-token"));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertFalse(NovaRequestContext.hasRuleBreak());
    }

    @Test
    void storesHashInsteadOfRawRuleBreakToken() {
        String raw = "valid-rulebreak-token-123456";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RuleBreakInterceptor.HDR, raw);
        request.addHeader("X-RuleBreak-Policy", "OVERRIDE_DOMAINS");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuleBreakInterceptor interceptor = new RuleBreakInterceptor(evaluator(raw));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        RuleBreakContext ctx = NovaRequestContext.getRuleBreak();
        assertTrue(ctx.enabled());
        assertEquals(RuleBreakContext.Policy.ALL_DOMAIN, ctx.policy());
        assertTrue(ctx.token().length() >= 32);
        assertFalse(ctx.token().contains(raw));

        interceptor.afterCompletion(request, response, new Object(), null);
        assertFalse(NovaRequestContext.hasRuleBreak());
    }

    private static RuleBreakEvaluator evaluator(String token) {
        RuleBreakEvaluator evaluator = new RuleBreakEvaluator();
        ReflectionTestUtils.setField(evaluator, "adminToken", token);
        ReflectionTestUtils.setField(evaluator, "defaultTtl", 60);
        return evaluator;
    }
}
