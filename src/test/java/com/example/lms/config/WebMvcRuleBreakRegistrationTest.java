package com.example.lms.config;

import com.example.lms.common.ReqLogInterceptor;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakContextHolder;
import com.example.lms.guard.rulebreak.RuleBreakEvaluator;
import com.example.lms.guard.rulebreak.RuleBreakInterceptor;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;
import com.example.lms.search.TraceStore;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebMvcRuleBreakRegistrationTest {
    private static final String SYNTHETIC_TOKEN = "synthetic-rb-mvc-proof";
    private static final String STATE_PATH = "/__test__/rulebreak/state";

    @AfterEach
    void clearContext() {
        RuleBreakContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void canonicalConfigurationCreatesBeanButLeavesItUnmappedAndTokenUnused() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(false)) {
            assertEquals(1, context.getBeansOfType(RuleBreakInterceptor.class).size());
            assertEquals(0, canonicalMappingCount(context));
            mvc(context).perform(get(STATE_PATH).header("X-RuleBreak-Token", SYNTHETIC_TOKEN))
                    .andExpect(status().isOk()).andExpect(content().string("inactive"));
            assertEquals(1, context.getBean(ProbeController.class).calls);
            verifyNoInteractions(context.getBean(RuleBreakEvaluator.class));
            assertNull(RuleBreakContextHolder.get());
        }
    }

    @ParameterizedTest(name = "test-only registration: {0}")
    @CsvSource({"valid,active", "invalid,inactive", "missing,inactive"})
    void explicitTestOnlyRegistrationUsesRealTokenGateAndClearsEveryRequest(String mode, String expected) throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(true)) {
            assertEquals(1, canonicalMappingCount(context));
            RuleBreakContextHolder.set(RuleBreakContext.active(RuleBreakPolicy.SAFE_EXPLORE,
                    "synthetic-prior-hash", Instant.now().plusSeconds(60), null, null));
            MockHttpServletRequestBuilder request = get(STATE_PATH);
            if (mode.equals("valid")) request.header("X-RuleBreak-Token", SYNTHETIC_TOKEN);
            if (mode.equals("invalid")) request.header("X-RuleBreak-Token", "synthetic-rb-mvc-wrong");
            MockMvc mvc = mvc(context);
            mvc.perform(request).andExpect(status().isOk()).andExpect(content().string(expected));
            assertNull(RuleBreakContextHolder.get());
            mvc.perform(get(STATE_PATH)).andExpect(status().isOk()).andExpect(content().string("inactive"));
            assertNull(RuleBreakContextHolder.get());
            assertEquals(2, context.getBean(ProbeController.class).calls);
            verify(context.getBean(RuleBreakEvaluator.class), times(2)).evaluateFromHeaders(any());
        }
    }

    @Test
    void explicitTestOnlyRegistrationClearsContextAfterControllerFailure() {
        try (AnnotationConfigWebApplicationContext context = context(true)) {
            assertThrows(ServletException.class, () -> mvc(context).perform(
                    get("/__test__/rulebreak/failure").header("X-RuleBreak-Token", SYNTHETIC_TOKEN)));
            assertTrue(context.getBean(ProbeController.class).activeAtFailure);
            assertNull(RuleBreakContextHolder.get());
            verify(context.getBean(RuleBreakEvaluator.class)).evaluateFromHeaders(any());
        }
    }

    @Test
    void explicitTestOnlyRegistrationDoesNotApplyOutsideItsDeclaredProofPaths() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(true)) {
            mvc(context).perform(get("/__test__/outside").header("X-RuleBreak-Token", SYNTHETIC_TOKEN))
                    .andExpect(status().isOk()).andExpect(content().string("inactive"));
            verifyNoInteractions(context.getBean(RuleBreakEvaluator.class));
            assertNull(RuleBreakContextHolder.get());
        }
    }

    private static long canonicalMappingCount(AnnotationConfigWebApplicationContext context) {
        RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
        Object value = ReflectionTestUtils.getField(mapping, "adaptedInterceptors");
        assertNotNull(value);
        return ((List<?>) value).stream()
                .map(entry -> entry instanceof MappedInterceptor mapped ? mapped.getInterceptor() : entry)
                .filter(RuleBreakInterceptor.class::isInstance).count();
    }

    private static MockMvc mvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static AnnotationConfigWebApplicationContext context(boolean addProofRegistration) {
        RuleBreakContextHolder.clear();
        TraceStore.clear();
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "nova.rulebreak.admin-token=" + SYNTHETIC_TOKEN, "nova.rulebreak.ttl-seconds=60");
        context.register(MvcFixture.class, ProbeController.class);
        if (addProofRegistration) context.register(TestOnlyRegistration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(WebMvcConfig.class)
    static class MvcFixture {
        @Bean
        ReqLogInterceptor reqLogInterceptor() throws Exception {
            ReqLogInterceptor interceptor = mock(ReqLogInterceptor.class);
            when(interceptor.preHandle(any(), any(), any())).thenReturn(true);
            return interceptor;
        }

        @Bean
        RuleBreakEvaluator ruleBreakEvaluator() {
            return spy(new RuleBreakEvaluator());
        }

        @Bean
        RuleBreakInterceptor ruleBreakInterceptor(RuleBreakEvaluator evaluator, ObjectProvider<DebugEventStore> events) {
            return new RuleBreakInterceptor(evaluator, events);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestOnlyRegistration implements WebMvcConfigurer {
        private final RuleBreakInterceptor interceptor;

        TestOnlyRegistration(RuleBreakInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).addPathPatterns("/__test__/rulebreak/**");
        }
    }

    @RestController
    static class ProbeController {
        int calls;
        boolean activeAtFailure;

        @GetMapping({STATE_PATH, "/__test__/outside"})
        String state() {
            calls++;
            RuleBreakContext context = RuleBreakContextHolder.get();
            return context != null && context.isValid() ? "active" : "inactive";
        }

        @GetMapping("/__test__/rulebreak/failure")
        String failure() {
            calls++;
            RuleBreakContext context = RuleBreakContextHolder.get();
            activeAtFailure = context != null && context.isValid();
            throw new IllegalStateException("synthetic controller failure");
        }
    }
}
