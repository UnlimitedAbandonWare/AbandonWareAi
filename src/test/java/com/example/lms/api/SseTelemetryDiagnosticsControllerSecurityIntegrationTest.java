package com.example.lms.api;

import com.example.lms.config.AppSecurityConfig;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.service.AdminService;
import com.example.lms.telemetry.LoggingSseEventPublisher;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SseTelemetryDiagnosticsController.class, useDefaultFilters = false)
@AutoConfigureMockMvc(addFilters = false)
@Import({SseTelemetryDiagnosticsController.class, AppSecurityConfig.class,
        AdminTokenGuardInterceptor.class, SseTelemetryDiagnosticsControllerSecurityIntegrationTest.TestConfig.class})
@TestPropertySource(properties = {
        "domain.allowlist.admin-token=r07-synthetic-admin-token",
        "domain.allowlist.admin-token.required=true",
        "security.bootstrap-admin.password="
})
@Timeout(10)
class SseTelemetryDiagnosticsControllerSecurityIntegrationTest {
    private static final String ENDPOINT = "/api/diagnostics/sse/events/stream";
    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy filterChain;
    @Autowired private ObjectMapper mapper;
    @SpyBean private SseTelemetryDiagnosticsController controller;
    @SpyBean private LoggingSseEventPublisher publisher;
    @MockBean private AdministratorRepository administratorRepository;
    @MockBean private AdminDetailsServiceImpl adminDetailsService;
    @MockBean private AdminService adminService;
    private MockMvc mvc;
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();
    private final AtomicReference<SignalType> terminal = new AtomicReference<>();
    private final CountDownLatch terminated = new CountDownLatch(1);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void installActualSelectedChainAndObserveUnmodifiedStream() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain).build();
        doAnswer(invocation -> ((Flux<Map<String, Object>>) invocation.callRealMethod())
                .doOnSubscribe(value -> { subscription.set(value); subscriptions.incrementAndGet(); })
                .doFinally(value -> { terminal.set(value); terminated.countDown(); }))
                .when(controller).stream();
    }

    @AfterEach
    void cleanupOwnedSubscriptionAndSyntheticContext() {
        Subscription value = subscription.get();
        if (value != null) value.cancel();
        TraceStore.clear();
        SecurityContextHolder.clearContext();
        org.slf4j.MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "non_admin", "invalid_token"})
    void disallowedExactEndpointRequestNeverInvokesControllerOrSubscribes(String mode) throws Exception {
        var requestBuilder = get(ENDPOINT).accept(MediaType.TEXT_EVENT_STREAM);
        if ("non_admin".equals(mode)) {
            var authentication = new UsernamePasswordAuthenticationToken("synthetic-user", null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            assertTrue(authentication.isAuthenticated());
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    new SecurityContextImpl(authentication));
            requestBuilder.session(session);
        } else if ("invalid_token".equals(mode)) {
            requestBuilder.header(AdminTokenGuardInterceptor.HEADER, "r07-invalid-synthetic-token");
        }
        mvc.perform(requestBuilder).andExpect(status().isForbidden()).andExpect(request().asyncNotStarted());
        verify(controller, never()).stream();
        verify(publisher, never()).asStream();
        assertEquals(0, subscriptions.get());
        System.out.printf("R07_SECURITY case=%s status=403 controllerCalls=0 subscriptions=0%n", mode);
    }

    @Test
    void validAdminPresentationReceivesGlobalReplayThenServletTimeoutCancelsStream() throws Exception {
        publisher.emit("r07_a", Map.of("count", 1), "synthetic-session-a");
        publisher.emit("r07_b", Map.of("count", 2), "synthetic-session-b");
        publisher.emit("r07_global", Map.of("count", 3));
        MvcResult result = null;
        try {
            result = mvc.perform(get(ENDPOINT).accept(MediaType.TEXT_EVENT_STREAM)
                            .header(AdminTokenGuardInterceptor.HEADER, "r07-synthetic-admin-token"))
                    .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            String body = result.getResponse().getContentAsString();
            while (!body.contains("r07_global") && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
                body = result.getResponse().getContentAsString();
            }
            assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(
                    MediaType.parseMediaType(result.getResponse().getContentType())));
            List<JsonNode> events = new ArrayList<>();
            for (String line : body.lines().filter(value -> value.startsWith("data:")).toList()) {
                events.add(mapper.readTree(line.substring(5)));
            }
            assertEquals(4, events.size());
            assertEquals(1L, events.stream().filter(e -> "hello".equals(e.path("type").asText())).count());
            assertEquals(SafeRedactor.hashValue("synthetic-session-a"), event(events, "r07_a").path("sessionId").asText());
            assertEquals(SafeRedactor.hashValue("synthetic-session-b"), event(events, "r07_b").path("sessionId").asText());
            assertFalse(event(events, "r07_global").has("sessionId"));
            assertFalse(body.contains("synthetic-session-a"));
            assertFalse(body.contains("synthetic-session-b"));
            verify(controller, times(1)).stream();
            verify(publisher, times(1)).asStream();
            assertEquals(1, subscriptions.get());
            MockAsyncContext asyncContext = (MockAsyncContext) result.getRequest().getAsyncContext();
            assertFalse(asyncContext.getListeners().isEmpty());
            var timeout = new jakarta.servlet.AsyncEvent(asyncContext, result.getRequest(), result.getResponse());
            for (var listener : List.copyOf(asyncContext.getListeners())) listener.onTimeout(timeout);
            assertTrue(terminated.await(2, TimeUnit.SECONDS));
            assertEquals(SignalType.CANCEL, terminal.get());
            asyncContext.complete();
            assertFalse(result.getRequest().isAsyncStarted());
            System.out.println("R07_SECURITY case=valid_admin status=200 controllerCalls=1 subscriptions=1 "
                    + "hello=1 hashedSessions=2 sessionless=1 servletTimeoutCancelled=true normalCompletionObserved=true");
        } finally {
            if (result != null && result.getRequest().isAsyncStarted()) {
                ((MockAsyncContext) result.getRequest().getAsyncContext()).complete();
            }
        }
    }

    private static JsonNode event(List<JsonNode> events, String type) {
        return events.stream().filter(value -> type.equals(value.path("type").asText())).findFirst().orElseThrow();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean LoggingSseEventPublisher publisher() { return new LoggingSseEventPublisher(8); }
    }
}
