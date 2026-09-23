package com.example.lms.api;

import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.ops.RagOpsLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RagOpsLedgerDiagnosticsControllerTest {

    @Test
    void summaryDelegatesClampedWindowWithoutRawPayload() throws Exception {
        RagOpsLedgerService service = mock(RagOpsLedgerService.class);
        when(service.summary(24)).thenReturn(Map.of(
                "enabled", true,
                "total", 1,
                "byDecision", Map.of("OK", 1)));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/diagnostics/rag/ops-ledger/summary").param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byDecision.OK").value(1))
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.snippet").doesNotExist())
                .andExpect(jsonPath("$.ownerToken").doesNotExist());

        verify(service).summary(24);
    }

    @Test
    void recentSupportsFiltersAndLimit() throws Exception {
        RagOpsLedgerService service = mock(RagOpsLedgerService.class);
        when(service.isEnabled()).thenReturn(true);
        when(service.recent("RAG_RUN", "OK", 5)).thenReturn(List.of(Map.of(
                "entryType", "RAG_RUN",
                "decision", "OK",
                "queryHash", "hash-only",
                "sourceCounts", Map.of("resultCount", 2))));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/diagnostics/rag/ops-ledger/recent")
                        .param("entryType", "RAG_RUN")
                        .param("decision", "OK")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.items[0].entryType").value("RAG_RUN"))
                .andExpect(jsonPath("$.items[0].decision").value("OK"))
                .andExpect(jsonPath("$.items[0].queryHash").value("hash-only"))
                .andExpect(jsonPath("$.items[0].sourceCounts.resultCount").value(2))
                .andExpect(jsonPath("$.items[0].query").doesNotExist())
                .andExpect(jsonPath("$.items[0].answer").doesNotExist())
                .andExpect(jsonPath("$.items[0].snippet").doesNotExist());

        verify(service).recent("RAG_RUN", "OK", 5);
    }

    @Test
    void diagnosticsAllowsExistingPolicyWhenAdminTokenUnset() throws Exception {
        RagOpsLedgerService service = mock(RagOpsLedgerService.class);
        when(service.summary(24)).thenReturn(Map.of("enabled", true, "total", 0));
        MockMvc mvc = mvcWithAdminGuard(service, "");

        mvc.perform(get("/api/diagnostics/rag/ops-ledger/summary").param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(service).summary(24);
    }

    @Test
    void diagnosticsRejectsWrongAdminTokenWhenConfigured() throws Exception {
        RagOpsLedgerService service = mock(RagOpsLedgerService.class);
        MockMvc mvc = mvcWithAdminGuard(service, "expected-token");

        mvc.perform(get("/api/diagnostics/rag/ops-ledger/summary")
                        .header(AdminTokenGuardInterceptor.HEADER, "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin token required"));

        verifyNoInteractions(service);
    }

    @Test
    void diagnosticsAcceptsConfiguredAdminTokenHeader() throws Exception {
        RagOpsLedgerService service = mock(RagOpsLedgerService.class);
        when(service.summary(24)).thenReturn(Map.of("enabled", true, "total", 0));
        MockMvc mvc = mvcWithAdminGuard(service, "expected-token");

        mvc.perform(get("/api/diagnostics/rag/ops-ledger/summary")
                        .header(AdminTokenGuardInterceptor.HEADER, "expected-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(service).summary(24);
    }

    private static MockMvc mvc(RagOpsLedgerService service) {
        return standaloneSetup(new RagOpsLedgerDiagnosticsController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static MockMvc mvcWithAdminGuard(RagOpsLedgerService service, String expectedToken) {
        AdminTokenGuardInterceptor interceptor = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(interceptor, "expectedToken", expectedToken);
        return standaloneSetup(new RagOpsLedgerDiagnosticsController(service))
                .addInterceptors(interceptor)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
