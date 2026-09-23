package com.example.lms.api;

import com.example.lms.service.rag.langgraph.LangGraphContaminationReplayService;
import com.example.lms.service.rag.langgraph.LangGraphContaminationReport;
import com.example.lms.service.rag.langgraph.LangGraphContaminationReportStore;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LangGraphContaminationDiagnosticsControllerTest {

    @Test
    void replayPostRejectsMissingCsrfToken() throws Exception {
        LangGraphContaminationReplayService replayService = mock(LangGraphContaminationReplayService.class);
        MockMvc mvc = mvc(replayService, csrfFilter(new HttpSessionCsrfTokenRepository()));

        mvc.perform(post("/api/diagnostics/langgraph-contamination/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"clean\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(replayService);
    }

    @Test
    void replayPostAcceptsValidCsrfToken() throws Exception {
        LangGraphContaminationReplayService replayService = mock(LangGraphContaminationReplayService.class);
        when(replayService.replay(any())).thenReturn(sampleReport());
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        MockMvc mvc = mvc(replayService, csrfFilter(repository));
        MockHttpSession session = new MockHttpSession();
        CsrfToken token = saveToken(repository, session);

        mvc.perform(post("/api/diagnostics/langgraph-contamination/replay")
                        .session(session)
                        .header(token.getHeaderName(), token.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"clean\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run"));

        verify(replayService).replay(any());
    }

    private static MockMvc mvc(LangGraphContaminationReplayService replayService, Filter filter) {
        return standaloneSetup(new LangGraphContaminationDiagnosticsController(
                replayService,
                new LangGraphContaminationReportStore()))
                .addFilters(filter)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static CsrfFilter csrfFilter(HttpSessionCsrfTokenRepository repository) {
        CsrfFilter filter = new CsrfFilter(repository);
        filter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        return filter;
    }

    private static CsrfToken saveToken(HttpSessionCsrfTokenRepository repository, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);
        return token;
    }

    private static LangGraphContaminationReport sampleReport() {
        return new LangGraphContaminationReport(
                "run",
                "thread",
                "query",
                "offline-replay",
                List.of(),
                new LangGraphContaminationReport.ContaminationSummary("none", "none", 0.0d, Map.of()),
                Map.of("schemaVersion", "test"));
    }
}
