package com.example.lms.api;

import com.example.lms.config.AppSecurityConfig;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.service.AdminService;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.TraceMemoryFingerprintProbe;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TraceSnapshotsDiagnosticsController.class, useDefaultFilters = false)
@AutoConfigureMockMvc(addFilters = false)
@Import({TraceSnapshotsDiagnosticsController.class, AppSecurityConfig.class, AdminTokenGuardInterceptor.class,
        TraceSnapshotsDiagnosticsSecurityIntegrationTest.TestConfig.class})
@TestPropertySource(properties = {
        "domain.allowlist.admin-token=integration-admin-token",
        "domain.allowlist.admin-token.required=true",
        "security.bootstrap-admin.password=",
        "trace.snapshot.enabled=true",
        "trace.snapshot.max-entries=100",
        "trace.snapshot.capture.min-interval-ms=0",
        "trace.snapshot.capture.max-per-trace=100"
})
class TraceSnapshotsDiagnosticsSecurityIntegrationTest {

    private static final String ADMIN_TOKEN = "integration-admin-token";
    private static final String RAW_REQUEST_ID = "raw-parent-request-id-must-not-leak";
    private static final String RAW_TRACE_ID = "raw-parent-trace-id-must-not-leak";
    private static final String RAW_QUERY = "raw-query-must-not-leak";
    private static final String RAW_AUTHORIZATION = "raw-authorization-must-not-leak";
    private static final List<String> APPROVED_NODE_HASH_KEYS = List.of(
            "ensemble.node.support.requestHash",
            "ensemble.node.support.traceHash",
            "ensemble.node.support.promptHash",
            "ensemble.node.support.optionsHash",
            "ensemble.node.support_alternative.requestHash",
            "ensemble.node.support_alternative.traceHash",
            "ensemble.node.support_alternative.promptHash",
            "ensemble.node.support_alternative.optionsHash",
            "ensemble.node.falsify.requestHash",
            "ensemble.node.falsify.traceHash",
            "ensemble.node.falsify.promptHash",
            "ensemble.node.falsify.optionsHash",
            "ensemble.node.cooperative.requestHash",
            "ensemble.node.cooperative.traceHash",
            "ensemble.node.cooperative.promptHash",
            "ensemble.node.cooperative.optionsHash",
            "ensemble.node.base_rate.requestHash",
            "ensemble.node.base_rate.traceHash",
            "ensemble.node.base_rate.promptHash",
            "ensemble.node.base_rate.optionsHash",
            "ensemble.node.opportunistic.requestHash",
            "ensemble.node.opportunistic.traceHash",
            "ensemble.node.opportunistic.promptHash",
            "ensemble.node.opportunistic.optionsHash");
    private static final List<String> APPROVED_ELAPSED_KEYS = List.of(
            "ensemble.node.support.modelCallElapsedMs",
            "ensemble.node.support_alternative.modelCallElapsedMs",
            "ensemble.node.falsify.modelCallElapsedMs",
            "ensemble.node.cooperative.modelCallElapsedMs",
            "ensemble.node.base_rate.modelCallElapsedMs",
            "ensemble.node.opportunistic.modelCallElapsedMs",
            "ensemble.judge.modelCallElapsedMs",
            "debug.triadic.judge.modelCallElapsedMs");
    private static final List<String> APPROVED_OBSERVABILITY_KEYS = List.of(
            "ensemble.node.support.requestHash",
            "ensemble.node.support.traceHash",
            "ensemble.node.support.promptHash",
            "ensemble.node.support.optionsHash",
            "ensemble.node.support.modelCallElapsedMs",
            "ensemble.node.support_alternative.requestHash",
            "ensemble.node.support_alternative.traceHash",
            "ensemble.node.support_alternative.promptHash",
            "ensemble.node.support_alternative.optionsHash",
            "ensemble.node.support_alternative.modelCallElapsedMs",
            "ensemble.node.falsify.requestHash",
            "ensemble.node.falsify.traceHash",
            "ensemble.node.falsify.promptHash",
            "ensemble.node.falsify.optionsHash",
            "ensemble.node.falsify.modelCallElapsedMs",
            "ensemble.node.cooperative.requestHash",
            "ensemble.node.cooperative.traceHash",
            "ensemble.node.cooperative.promptHash",
            "ensemble.node.cooperative.optionsHash",
            "ensemble.node.cooperative.modelCallElapsedMs",
            "ensemble.node.base_rate.requestHash",
            "ensemble.node.base_rate.traceHash",
            "ensemble.node.base_rate.promptHash",
            "ensemble.node.base_rate.optionsHash",
            "ensemble.node.base_rate.modelCallElapsedMs",
            "ensemble.node.opportunistic.requestHash",
            "ensemble.node.opportunistic.traceHash",
            "ensemble.node.opportunistic.promptHash",
            "ensemble.node.opportunistic.optionsHash",
            "ensemble.node.opportunistic.modelCallElapsedMs",
            "ensemble.judge.modelCallElapsedMs",
            "debug.triadic.judge.modelCallElapsedMs");

    private MockMvc mvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private TraceSnapshotStore snapshots;

    @MockBean
    private AdministratorRepository administratorRepository;

    @MockBean
    private AdminDetailsServiceImpl adminDetailsService;

    @MockBean
    private AdminService adminService;

    @MockBean
    private TraceMemoryFingerprintProbe traceMemoryProbe;

    private String snapshotId;

    @BeforeEach
    void seedSnapshotAndCheckpoint() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
        when(traceMemoryProbe.checkpoint(anyString(), anyString(), anyMap()))
                .thenReturn(new TraceMemoryFingerprintProbe.Checkpoint(
                        "load", "integration_test", "hash:0123456789ab", null,
                        0, 0, 0, 0, false, "none", ""));

        snapshotId = snapshots.captureCustom(
                "task4_integration",
                "GET",
                "/api/chat",
                200,
                null,
                Map.ofEntries(
                        Map.entry("ensemble.node.support.requestHash", "hash:111111111111"),
                        Map.entry("ensemble.node.support.traceHash", "hash:222222222222"),
                        Map.entry("ensemble.node.support.promptHash", "hash:333333333333"),
                        Map.entry("ensemble.node.support.optionsHash", "hash:444444444444"),
                        Map.entry("ensemble.node.support.modelCallElapsedMs", 17L),
                        Map.entry("ensemble.judge.tokenUsageObserved", true),
                        Map.entry("ensemble.judge.inputTokens", 11),
                        Map.entry("ensemble.judge.outputTokens", 7),
                        Map.entry("ensemble.judge.totalTokens", 18),
                        Map.entry("ensemble.judge.modelCallElapsedMs", 19L),
                        Map.entry("debug.triadic.judge.tokenUsageObserved", false),
                        Map.entry("debug.triadic.judge.tokenUsageReason", "provider_usage_unavailable"),
                        Map.entry("debug.triadic.judge.modelCallElapsedMs", 23L),
                        Map.entry("requestId", RAW_REQUEST_ID),
                        Map.entry("traceId", RAW_TRACE_ID),
                        Map.entry("rawQuery", RAW_QUERY),
                        Map.entry("authorization", RAW_AUTHORIZATION)),
                null);
        assertNotNull(snapshotId);
    }

    @Test
    void adminGetExposesOnlySafeSeededEnsembleDiagnostics() throws Exception {
        mvc.perform(get("/api/diagnostics/trace/snapshots/{id}", snapshotId)
                        .header(AdminTokenGuardInterceptor.HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$['trace']['ensemble.node.support.requestHash']").value("hash:111111111111"))
                .andExpect(jsonPath("$['trace']['ensemble.node.support.traceHash']").value("hash:222222222222"))
                .andExpect(jsonPath("$['trace']['ensemble.node.support.promptHash']").value("hash:333333333333"))
                .andExpect(jsonPath("$['trace']['ensemble.node.support.optionsHash']").value("hash:444444444444"))
                .andExpect(jsonPath("$['trace']['ensemble.node.support.modelCallElapsedMs']").value(17))
                .andExpect(jsonPath("$['trace']['ensemble.judge.tokenUsageObserved']").value(true))
                .andExpect(jsonPath("$['trace']['ensemble.judge.inputTokens']").value(11))
                .andExpect(jsonPath("$['trace']['ensemble.judge.outputTokens']").value(7))
                .andExpect(jsonPath("$['trace']['ensemble.judge.totalTokens']").value(18))
                .andExpect(jsonPath("$['trace']['ensemble.judge.modelCallElapsedMs']").value(19))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.tokenUsageObserved']").value(false))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.tokenUsageReason']").value("provider_usage_unavailable"))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.modelCallElapsedMs']").value(23))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.inputTokens']").doesNotExist())
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.outputTokens']").doesNotExist())
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.totalTokens']").doesNotExist())
                .andExpect(jsonPath("$['trace']['authorization']").doesNotExist())
                .andExpect(content().string(not(containsString(RAW_REQUEST_ID))))
                .andExpect(content().string(not(containsString(RAW_TRACE_ID))))
                .andExpect(content().string(not(containsString(RAW_QUERY))))
                .andExpect(content().string(not(containsString(RAW_AUTHORIZATION))));
    }

    @Test
    void adminGetProjectsOppositeNormalAndDebugUsageShapesWithoutSynthesizingCounts() throws Exception {
        String id = capture(Map.ofEntries(
                Map.entry("ensemble.judge.tokenUsageObserved", false),
                Map.entry("ensemble.judge.tokenUsageReason", "provider_usage_unavailable"),
                Map.entry("ensemble.judge.modelCallElapsedMs", 0L),
                Map.entry("debug.triadic.judge.tokenUsageObserved", true),
                Map.entry("debug.triadic.judge.inputTokens", 0),
                Map.entry("debug.triadic.judge.outputTokens", 5),
                Map.entry("debug.triadic.judge.totalTokens", 5),
                Map.entry("debug.triadic.judge.modelCallElapsedMs", 1L)));

        adminGet(id)
                .andExpect(jsonPath("$['trace']['ensemble.judge.tokenUsageObserved']").value(false))
                .andExpect(jsonPath("$['trace']['ensemble.judge.tokenUsageReason']").value("provider_usage_unavailable"))
                .andExpect(jsonPath("$['trace']['ensemble.judge.inputTokens']").doesNotExist())
                .andExpect(jsonPath("$['trace']['ensemble.judge.outputTokens']").doesNotExist())
                .andExpect(jsonPath("$['trace']['ensemble.judge.totalTokens']").doesNotExist())
                .andExpect(jsonPath("$['trace']['ensemble.judge.modelCallElapsedMs']").value(0))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.tokenUsageObserved']").value(true))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.inputTokens']").value(0))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.outputTokens']").value(5))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.totalTokens']").value(5))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.modelCallElapsedMs']").value(1));
    }

    @Test
    void adminGetKeepsInvalidUsageTypesValuesAndNearMatchesRedacted() throws Exception {
        List<Map.Entry<String, Object>> scalarCases = List.of(
                Map.entry("ensemble.judge.tokenUsageObserved", "true"),
                Map.entry("debug.triadic.judge.tokenUsageObserved", 1),
                Map.entry("ensemble.judge.inputTokens", -1),
                Map.entry("ensemble.judge.outputTokens", "7"),
                Map.entry("ensemble.judge.totalTokens", true),
                Map.entry("debug.triadic.judge.inputTokens", 1.5d),
                Map.entry("debug.triadic.judge.outputTokens", Double.NaN),
                Map.entry("debug.triadic.judge.totalTokens", Double.POSITIVE_INFINITY),
                Map.entry("ensemble.judge.inputTokens", new BigInteger("999999999999999999999999999999")),
                Map.entry("ensemble.judge.outputTokens", new BigDecimal("7.0")),
                Map.entry("debug.triadic.judge.totalTokens", new CustomNumber()),
                Map.entry("ensemble.judge.tokenUsageReason", "raw_exception_text_must_not_leak"),
                Map.entry("debug.triadic.judge.tokenUsageReason", "request_id_must_not_leak"),
                Map.entry("ensemble.judge.inputTokens.extra", 11),
                Map.entry("ensemble.judge.tokenUsageObservedRaw", true),
                Map.entry("unapproved.judge.inputTokens", 11));

        for (Map.Entry<String, Object> entry : scalarCases) {
            String id = capture(Map.of(entry.getKey(), entry.getValue()));
            adminGet(id).andExpect(jsonPath("$['trace']['" + entry.getKey() + "']").value("(redacted)"));
        }

        String mapId = capture(Map.of(
                "ensemble.judge.tokenUsageObserved", Map.of("raw", "map-value-must-not-leak")));
        adminGet(mapId).andExpect(content().string(not(containsString("map-value-must-not-leak"))));

        String listId = capture(Map.of(
                "debug.triadic.judge.tokenUsageObserved", List.of("list-value-must-not-leak")));
        adminGet(listId).andExpect(content().string(not(containsString("list-value-must-not-leak"))));
    }

    @Test
    void adminGetProjectsOnlyTheThirtyTwoExactNodeAndJudgeObservabilityKeys() throws Exception {
        assertEquals(32, APPROVED_OBSERVABILITY_KEYS.size());
        assertEquals(APPROVED_OBSERVABILITY_KEYS,
                List.of(
                        "ensemble.node.support.requestHash",
                        "ensemble.node.support.traceHash",
                        "ensemble.node.support.promptHash",
                        "ensemble.node.support.optionsHash",
                        "ensemble.node.support.modelCallElapsedMs",
                        "ensemble.node.support_alternative.requestHash",
                        "ensemble.node.support_alternative.traceHash",
                        "ensemble.node.support_alternative.promptHash",
                        "ensemble.node.support_alternative.optionsHash",
                        "ensemble.node.support_alternative.modelCallElapsedMs",
                        "ensemble.node.falsify.requestHash",
                        "ensemble.node.falsify.traceHash",
                        "ensemble.node.falsify.promptHash",
                        "ensemble.node.falsify.optionsHash",
                        "ensemble.node.falsify.modelCallElapsedMs",
                        "ensemble.node.cooperative.requestHash",
                        "ensemble.node.cooperative.traceHash",
                        "ensemble.node.cooperative.promptHash",
                        "ensemble.node.cooperative.optionsHash",
                        "ensemble.node.cooperative.modelCallElapsedMs",
                        "ensemble.node.base_rate.requestHash",
                        "ensemble.node.base_rate.traceHash",
                        "ensemble.node.base_rate.promptHash",
                        "ensemble.node.base_rate.optionsHash",
                        "ensemble.node.base_rate.modelCallElapsedMs",
                        "ensemble.node.opportunistic.requestHash",
                        "ensemble.node.opportunistic.traceHash",
                        "ensemble.node.opportunistic.promptHash",
                        "ensemble.node.opportunistic.optionsHash",
                        "ensemble.node.opportunistic.modelCallElapsedMs",
                        "ensemble.judge.modelCallElapsedMs",
                        "debug.triadic.judge.modelCallElapsedMs"));

        Map<String, Object> trace = new LinkedHashMap<>();
        for (int i = 0; i < APPROVED_NODE_HASH_KEYS.size(); i++) {
            trace.put(APPROVED_NODE_HASH_KEYS.get(i), String.format("hash:%012x", i + 1));
        }
        trace.put("ensemble.node.support.modelCallElapsedMs", 0L);
        trace.put("ensemble.node.support_alternative.modelCallElapsedMs", 17L);
        trace.put("ensemble.node.falsify.modelCallElapsedMs", 23L);
        trace.put("ensemble.node.cooperative.modelCallElapsedMs", 29L);
        trace.put("ensemble.node.base_rate.modelCallElapsedMs", 31L);
        trace.put("ensemble.node.opportunistic.modelCallElapsedMs", 37L);
        trace.put("ensemble.judge.modelCallElapsedMs", Long.MAX_VALUE);
        trace.put("debug.triadic.judge.modelCallElapsedMs", 1L);

        ResultActions result = adminGet(capture(trace));
        for (int i = 0; i < APPROVED_NODE_HASH_KEYS.size(); i++) {
            result.andExpect(jsonPath("$['trace']['" + APPROVED_NODE_HASH_KEYS.get(i) + "']")
                    .value(String.format("hash:%012x", i + 1)));
        }
        result.andExpect(jsonPath("$['trace']['ensemble.node.support.modelCallElapsedMs']").value(0))
                .andExpect(jsonPath("$['trace']['ensemble.node.support_alternative.modelCallElapsedMs']").value(17))
                .andExpect(jsonPath("$['trace']['ensemble.node.falsify.modelCallElapsedMs']").value(23))
                .andExpect(jsonPath("$['trace']['ensemble.node.cooperative.modelCallElapsedMs']").value(29))
                .andExpect(jsonPath("$['trace']['ensemble.node.base_rate.modelCallElapsedMs']").value(31))
                .andExpect(jsonPath("$['trace']['ensemble.node.opportunistic.modelCallElapsedMs']").value(37))
                .andExpect(jsonPath("$['trace']['ensemble.judge.modelCallElapsedMs']").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.modelCallElapsedMs']").value(1));
    }

    @Test
    void adminGetPrioritizesExactEnsembleObservabilityKeysInCrowdedTraces() throws Exception {
        Map<String, Object> trace = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            trace.put("harmless.metric." + i, i);
        }
        for (int i = 0; i < APPROVED_NODE_HASH_KEYS.size(); i++) {
            trace.put(APPROVED_NODE_HASH_KEYS.get(i), String.format("hash:%012x", i + 1));
        }
        trace.put("ensemble.node.support.modelCallElapsedMs", 0L);
        trace.put("ensemble.node.support_alternative.modelCallElapsedMs", 17L);
        trace.put("ensemble.node.falsify.modelCallElapsedMs", 23L);
        trace.put("ensemble.node.cooperative.modelCallElapsedMs", 29L);
        trace.put("ensemble.node.base_rate.modelCallElapsedMs", 31L);
        trace.put("ensemble.node.opportunistic.modelCallElapsedMs", 37L);
        trace.put("ensemble.judge.modelCallElapsedMs", 41L);
        trace.put("debug.triadic.judge.modelCallElapsedMs", 43L);

        ResultActions result = adminGet(capture(trace));
        for (int i = 0; i < APPROVED_NODE_HASH_KEYS.size(); i++) {
            result.andExpect(jsonPath("$['trace']['" + APPROVED_NODE_HASH_KEYS.get(i) + "']")
                    .value(String.format("hash:%012x", i + 1)));
        }
        result.andExpect(jsonPath("$['trace']['ensemble.node.support.modelCallElapsedMs']").value(0))
                .andExpect(jsonPath("$['trace']['ensemble.node.support_alternative.modelCallElapsedMs']").value(17))
                .andExpect(jsonPath("$['trace']['ensemble.node.falsify.modelCallElapsedMs']").value(23))
                .andExpect(jsonPath("$['trace']['ensemble.node.cooperative.modelCallElapsedMs']").value(29))
                .andExpect(jsonPath("$['trace']['ensemble.node.base_rate.modelCallElapsedMs']").value(31))
                .andExpect(jsonPath("$['trace']['ensemble.node.opportunistic.modelCallElapsedMs']").value(37))
                .andExpect(jsonPath("$['trace']['ensemble.judge.modelCallElapsedMs']").value(41))
                .andExpect(jsonPath("$['trace']['debug.triadic.judge.modelCallElapsedMs']").value(43));
    }

    @Test
    void adminGetRedactsEveryInvalidExactNodeHashValue() throws Exception {
        Object[] invalidValues = {
                "hash:12345678901",
                "hash:1234567890123",
                "hash:ABCDEF123456",
                "abcdef123456",
                " hash:abcdef123456",
                "hash:abcdef123456 ",
                "hash:abcdef123456\nsuffix",
                "prefix-hash:abcdef123456",
                "sk-"" + ""live-secret-shaped-hash-value",
                123,
                Map.of("raw", "map-hash-value-must-not-leak"),
                null
        };

        for (Object invalidValue : invalidValues) {
            Map<String, Object> trace = new LinkedHashMap<>();
            for (String key : APPROVED_NODE_HASH_KEYS) {
                trace.put(key, invalidValue);
            }
            ResultActions result = adminGet(capture(trace));
            for (String key : APPROVED_NODE_HASH_KEYS) {
                result.andExpect(jsonPath("$['trace']['" + key + "']").value("(redacted)"));
            }
            result.andExpect(content().string(not(containsString("sk-"" + ""live-secret-shaped-hash-value"))))
                    .andExpect(content().string(not(containsString("map-hash-value-must-not-leak"))));
        }
    }

    @Test
    void adminGetRedactsEveryInvalidExactElapsedValueWithoutNumberCoercion() throws Exception {
        List<Object> invalidValues = Arrays.asList(
                -1L,
                0,
                0.0d,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                "0",
                true,
                BigInteger.ZERO,
                BigDecimal.ZERO,
                new AtomicLong(0L),
                new CustomNumber(),
                Map.of("raw", "map-elapsed-value-must-not-leak"),
                null);

        for (Object invalidValue : invalidValues) {
            Map<String, Object> trace = new LinkedHashMap<>();
            for (String key : APPROVED_ELAPSED_KEYS) {
                trace.put(key, invalidValue);
            }
            ResultActions result = adminGet(capture(trace));
            for (String key : APPROVED_ELAPSED_KEYS) {
                result.andExpect(jsonPath("$['trace']['" + key + "']").value("(redacted)"));
            }
            result.andExpect(content().string(not(containsString("map-elapsed-value-must-not-leak"))));
        }
    }

    @Test
    void adminGetKeepsDynamicRolesFieldsAndJudgeNearMatchesOnTheGenericRedactionPath() throws Exception {
        List<String> nearMatchKeys = List.of(
                "ensemble.node.support2.requestHash",
                "ensemble.node.support_alt.requestHash",
                "ensemble.node.supportAlternative.requestHash",
                "ensemble.node.SUPPORT.requestHash",
                "ensemble.node.\u0455upport.requestHash",
                "ensemble.node.support..requestHash",
                "ensemble.node.support.extra.requestHash",
                "ensemble.node.support.responseHash",
                "ensemble.node.support.requestHash.extra",
                "ensemble.node.support.prerequestHash",
                "ensemble.node.support.modelCallElapsedMs.extra",
                "ensemble.node.support.modelCallElapsedM",
                "ensemble.node.cooperative2.requestHash",
                "ensemble.node.base-rate.requestHash",
                "ensemble.node.opportunistic.requestHash.extra",
                "ensemble.node.base_rate.modelCallElapsedM",
                "ensemble.judge.modelCallElapsedMs.extra",
                "ensemble.judgeX.modelCallElapsedMs",
                "debug.triadic.judge.modelCallElapsedM",
                "debug.triadic.judge.extra.modelCallElapsedMs");

        Map<String, Object> trace = new LinkedHashMap<>();
        for (int i = 0; i < nearMatchKeys.size(); i++) {
            trace.put(nearMatchKeys.get(i), "sk-live-near-match-" + i);
        }
        trace.put("token", "raw-token-control-must-not-leak");
        trace.put("authorization", "raw-authorization-control-must-not-leak");
        trace.put("rawQuery", "raw-query-control-must-not-leak");
        trace.put("requestId", "raw-request-control-must-not-leak");

        ResultActions result = adminGet(capture(trace));
        for (int i = 0; i < nearMatchKeys.size(); i++) {
            result.andExpect(content().string(not(containsString("sk-live-near-match-" + i))));
        }
        result.andExpect(content().string(not(containsString("raw-token-control-must-not-leak"))))
                .andExpect(content().string(not(containsString("raw-authorization-control-must-not-leak"))))
                .andExpect(content().string(not(containsString("raw-query-control-must-not-leak"))))
                .andExpect(content().string(not(containsString("raw-request-control-must-not-leak"))));
    }

    @Test
    void anonymousGetIsDeniedByTheActualDiagnosticsSecurityChain() throws Exception {
        mvc.perform(get("/api/diagnostics/trace/snapshots/{id}", snapshotId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));
    }

    @Test
    void adminPostRequiresCsrfAndReachesControllerWithMatchingCookieAndHeader() throws Exception {
        mvc.perform(post("/api/diagnostics/trace/memory/self-probe")
                        .header(AdminTokenGuardInterceptor.HEADER, ADMIN_TOKEN))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/diagnostics/trace/memory/self-probe")
                        .header(AdminTokenGuardInterceptor.HEADER, ADMIN_TOKEN)
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "task4-csrf-token"))
                        .header("X-XSRF-TOKEN", "task4-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("trace_memory_self_probe_captured"));
    }

    private String capture(Map<String, Object> trace) {
        String id = snapshots.captureCustom(
                "task4_integration",
                "GET",
                "/api/chat",
                200,
                null,
                trace,
                null);
        assertNotNull(id);
        return id;
    }

    private ResultActions adminGet(String id) throws Exception {
        return mvc.perform(get("/api/diagnostics/trace/snapshots/{id}", id)
                        .header(AdminTokenGuardInterceptor.HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private static final class CustomNumber extends Number {
        @Override
        public int intValue() {
            return 7;
        }

        @Override
        public long longValue() {
            return 7L;
        }

        @Override
        public float floatValue() {
            return 7.0f;
        }

        @Override
        public double doubleValue() {
            return 7.0d;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean("task4TraceSnapshotStore")
        @Primary
        TraceSnapshotStore traceSnapshotStore(ObjectProvider<TraceHtmlBuilder> htmlBuilderProvider) {
            TraceSnapshotStore store = new TraceSnapshotStore(htmlBuilderProvider);
            ReflectionTestUtils.setField(store, "enabled", true);
            ReflectionTestUtils.setField(store, "maxSize", 10);
            ReflectionTestUtils.setField(store, "maxValueLen", 2000);
            ReflectionTestUtils.setField(store, "maxEntries", 100);
            ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
            ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
            ReflectionTestUtils.setField(store, "allowKeysCsv", "");
            ReflectionTestUtils.setField(store, "allowKeysMode", "any");
            ReflectionTestUtils.setField(store, "denyKeysCsv", "");
            ReflectionTestUtils.setField(store, "captureSample", 1.0d);
            ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
            ReflectionTestUtils.setField(store, "maxPerTrace", 10);
            ReflectionTestUtils.setField(store, "budgetWindowMs", 600000L);
            ReflectionTestUtils.setField(store, "httpStatusMin", 400);
            ReflectionTestUtils.setField(store, "captureHttpOnDebug", true);
            ReflectionTestUtils.setField(store, "captureHttpOnMl", true);
            ReflectionTestUtils.setField(store, "captureHttpOnOrch", true);
            ReflectionTestUtils.setField(store, "captureHttpOnException", true);
            ReflectionTestUtils.setField(store, "htmlEnabled", false);
            ReflectionTestUtils.setField(store, "htmlMaxLen", 60000);
            return store;
        }
    }
}
