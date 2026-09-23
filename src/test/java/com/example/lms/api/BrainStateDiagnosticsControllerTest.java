package com.example.lms.api;

import com.example.lms.service.rag.graph.BrainSnapshot;
import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.DomainSummary;
import com.example.lms.service.rag.graph.KgEntityView;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BrainStateDiagnosticsControllerTest {

    @Test
    void diagnosticsExposeSnapshotDomainsAndEntities() throws Exception {
        String rawSessionId = "session-secret-12345";
        BrainStateService service = mock(BrainStateService.class);
        when(service.getBrainSnapshot(rawSessionId, "", 20)).thenReturn(new BrainSnapshot(
                rawSessionId, 1, 1,
                List.of(new DomainSummary("GENERAL", 1, 1, 0)),
                List.of(), List.of(), List.of(), Instant.parse("2026-01-01T00:00:00Z"),
                "disabled", "disabled"));
        when(service.getBrainSnapshot(rawSessionId, "GENERAL", 3)).thenReturn(new BrainSnapshot(
                rawSessionId, 1, 1,
                List.of(new DomainSummary("GENERAL", 1, 1, 0)),
                List.of(), List.of(), List.of(),
                List.of(new BrainSnapshot.SourceSummary("CHAT_HISTORY", "chat", "conversation", "manual", 1, 1, 0,
                        Instant.parse("2026-01-01T00:00:00Z"))),
                List.of(new BrainSnapshot.RecentChange("123456789abc", "abcdef123456", "GENERAL",
                        "CHAT_HISTORY", "chat", "conversation", "manual", 42, 1, 0,
                        Instant.parse("2026-01-01T00:00:00Z"))),
                new BrainSnapshot.AnchorMapSummary(true, "GENERAL", 1, 0,
                        List.of(new BrainSnapshot.AnchorEntry("789abc123456", "ENTITY", "GENERAL", 1, 0, 0.8)),
                        ""),
                new BrainSnapshot.QueryTimeSummary("success", true, true, 1,
                        List.of("789abc123456"), "cue_seeded_landmark_anchors", 1, 1, 1,
                        "", "", 7, "fedcba987654", Instant.parse("2026-01-01T00:00:00Z")),
                Instant.parse("2026-01-01T00:00:00Z"),
                "configured",
                ""));
        when(service.listKnownDomains()).thenReturn(List.of(new DomainSummary("GENERAL", 1, 1, 0)));
        when(service.listEntityNodes("GENERAL", 25))
                .thenReturn(List.of(new KgEntityView("Alpha", "ENTITY", "GENERAL", 1, 0.8)));
        MockMvc mvc = standaloneSetup(new BrainStateDiagnosticsController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        MvcResult snapshotResult = mvc.perform(get("/api/diagnostics/rag/brain-state/snapshot/{sessionId}", rawSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SafeRedactor.hash12(rawSessionId)))
                .andExpect(jsonPath("$.totalChunks").value(1))
                .andExpect(jsonPath("$.sourceText").doesNotExist())
                .andReturn();
        assertFalse(snapshotResult.getResponse().getContentAsString().contains(rawSessionId));

        MvcResult domainSnapshotResult = mvc.perform(get("/api/diagnostics/rag/brain-state/snapshot/{sessionId}", rawSessionId)
                        .param("domain", "GENERAL")
                        .param("recentLimit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SafeRedactor.hash12(rawSessionId)))
                .andExpect(jsonPath("$.sourceSummaries[0].sourceTag").value("CHAT_HISTORY"))
                .andExpect(jsonPath("$.recentChanges[0].chunkHash12").value("123456789abc"))
                .andExpect(jsonPath("$.recentChanges[0].sessionHash12").value("abcdef123456"))
                .andExpect(jsonPath("$.anchorMap.enabled").value(true))
                .andExpect(jsonPath("$.anchorMap.topAnchors[0].entityHash12").value("789abc123456"))
                .andExpect(jsonPath("$.queryTime.status").value("success"))
                .andExpect(jsonPath("$.queryTime.queryHash12").value("fedcba987654"))
                .andExpect(jsonPath("$.queryTime.query").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist())
                .andReturn();
        assertFalse(domainSnapshotResult.getResponse().getContentAsString().contains(rawSessionId));

        mvc.perform(get("/api/diagnostics/rag/brain-state/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.domains[0].domain").value("GENERAL"));

        mvc.perform(get("/api/diagnostics/rag/brain-state/entities/GENERAL").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Alpha"))
                .andExpect(jsonPath("$.items[0].domain").value("GENERAL"));
    }
}
