package com.example.lms.api;

import com.example.lms.learning.ops.RagLearningOpsDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RagLearningOpsDiagnosticsControllerTest {

    @Test
    void overviewDelegatesWithRequestedLimit() throws Exception {
        RagLearningOpsDashboardService service = mock(RagLearningOpsDashboardService.class);
        when(service.overview(25)).thenReturn(Map.of(
                "checkpoint", "[AWX][learning-ops]",
                "metrics", Map.of("sampleTotal", 2),
                "orchestrationOverlays", Map.of(
                        "activeCount", 3,
                        "zero100", Map.of("phase", "CONSENSUS"),
                        "hypernova", Map.of("active", true))));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/diagnostics/rag/learning-ops/overview").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint").value("[AWX][learning-ops]"))
                .andExpect(jsonPath("$.metrics.sampleTotal").value(2))
                .andExpect(jsonPath("$.orchestrationOverlays.activeCount").value(3))
                .andExpect(jsonPath("$.orchestrationOverlays.zero100.phase").value("CONSENSUS"))
                .andExpect(jsonPath("$.orchestrationOverlays.hypernova.active").value(true));

        verify(service).overview(25);
    }

    @Test
    void prometheusUsesTextExpositionContentType() throws Exception {
        RagLearningOpsDashboardService service = mock(RagLearningOpsDashboardService.class);
        when(service.prometheus()).thenReturn("""
                # HELP rag_learning_train_samples_total Parsed train_rag JSONL samples
                # TYPE rag_learning_train_samples_total gauge
                rag_learning_train_samples_total 2.0
                """);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/diagnostics/rag/learning-ops/metrics/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain;version=0.0.4;charset=utf-8")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("rag_learning_train_samples_total 2.0")));

        verify(service).prometheus();
    }

    private static MockMvc mvc(RagLearningOpsDashboardService service) {
        return standaloneSetup(new RagLearningOpsDiagnosticsController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(), new StringHttpMessageConverter())
                .build();
    }
}
