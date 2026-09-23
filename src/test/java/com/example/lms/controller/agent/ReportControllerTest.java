package com.example.lms.controller.agent;

import com.example.lms.dto.agent.CfvmSnapshotDto;
import com.example.lms.dto.agent.GatesSummaryDto;
import com.example.lms.dto.agent.HypernovaFusionDto;
import com.example.lms.dto.agent.MoeDecisionDto;
import com.example.lms.dto.agent.OverdriveStatusDto;
import com.example.lms.dto.agent.ReasonDto;
import com.example.lms.dto.agent.ScoreEventDto;
import com.example.lms.service.agent.ReportSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReportControllerTest {

    @Test
    void exposesAgentReportEndpoints() throws Exception {
        ReportSnapshotService service = mock(ReportSnapshotService.class);
        when(service.cfvmSnapshot()).thenReturn(new CfvmSnapshotDto(
                42L, "plan=other", 3, Map.of("extremez.risk.primaryCause", "provider_disabled")));
        when(service.moeDecision()).thenReturn(new MoeDecisionDto(
                "RG_ENSEMBLE", List.of("R_ONLY"), 140, 80, -1000,
                List.of(new ScoreEventDto("vector_poison_or_low_evidence", 40, 0, 0)),
                List.of(new ReasonDto("need_red_refit", 80, "RED")),
                "AP1_AUTH_WEB", 0.88d, "promote", 25));
        when(service.artplateStatus()).thenReturn(Map.of(
                "selectedPlate", "AP1_AUTH_WEB",
                "plateScore", 0.88d,
                "rolloutReason", "promote",
                "rolloutPercent", 25));
        when(service.overdriveStatus()).thenReturn(new OverdriveStatusDto(
                true, 0.91d, 0.55d, false, "threshold", 4,
                0.25d, 0.7d, 0.1d, 0.0d, null, null));
        when(service.hypernovaFusion()).thenReturn(new HypernovaFusionDto(
                1.0d, 0.8d, 0.6d, 0.35d, 24, true,
                "BiEncoder -> CrossEncoder -> DPP"));
        when(service.extremezStatus()).thenReturn(Map.of(
                "available", true,
                "activationReason", "risk"));
        when(service.memoryReinforcement()).thenReturn(Map.of(
                "available", true,
                "memoryEnabled", false,
                "reinforcementMode", "CONSERVATIVE"));
        when(service.gatesSummary()).thenReturn(new GatesSummaryDto(
                0.70d, "SOFT", false, 2, true, true, 7L, 1L, 2L));
        when(service.traceKpi()).thenReturn(Map.of(
                "agentReportEventCount", 1L,
                "boosterMode", Map.of("boosterMode.active", "EXTREMEZ"),
                "retrievalOrder", Map.of(
                        "retrievalOrder.lastSetBy", "PLAN_DSL",
                        "retrievalOrder.lastOrder", List.of("VECTOR", "KG", "WEB"),
                        "retrieval.order.strategy.applied", false),
                "routingPlan", Map.of(
                        "routing.executionPlan.primaryMode", "EXTREMEZ",
                        "routing.executionPlan.extremeZ", true,
                        "routing.executionPlan.overdrive", false,
                        "routing.executionPlan.hypernova", false,
                        "specialMode.conflict.suppressed", "OVERDRIVE_HYPERNOVA"),
                "hypernova", Map.of(
                        "hypernova.twpmP", 4.0d,
                        "hypernova.cvarPhi", 0.618d,
                        "hypernova.clampApplied", true,
                        "hypernova.dppApplied", true,
                        "nova.hypernova.riskK.alloc.sum", 12),
                "cihRag", Map.of(
                        "cihRag.mlaBreadcrumbCount", 2,
                        "cihRag.breadcrumb.queryRedacted", true),
                "matryoshka", Map.of(
                        "embed.matryoshka.slice.actual", 4096,
                        "embed.matryoshka.slice.target", 1536,
                        "embed.matryoshka.slice.reductionRatio", 0.625d),
                "localLlm", Map.of(
                        "localLlm.startup.status", "skipped",
                        "localLlm.startup.hostHash", "hash:hostabcd",
                        "localLlm.warmup.modelHash", "hash:modelabcd")));
        MockMvc mvc = standaloneSetup(new ReportController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        mvc.perform(get("/api/agent/report/cfvm/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPatternId").value(42))
                .andExpect(jsonPath("$.traceKeys['extremez.risk.primaryCause']").value("provider_disabled"));

        mvc.perform(get("/api/agent/report/moe/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryStrategy").value("RG_ENSEMBLE"))
                .andExpect(jsonPath("$.fallbackStrategies[0]").value("R_ONLY"))
                .andExpect(jsonPath("$.selectedPlate").value("AP1_AUTH_WEB"));

        mvc.perform(get("/api/agent/report/artplate/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedPlate").value("AP1_AUTH_WEB"))
                .andExpect(jsonPath("$.rolloutPercent").value(25));

        mvc.perform(get("/api/agent/report/overdrive/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.reason").value("threshold"));

        mvc.perform(get("/api/agent/report/hypernova/fusion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webWeight").value(1.0d))
                .andExpect(jsonPath("$.whiteningEnabled").value(true));

        mvc.perform(get("/api/agent/report/extremez/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.activationReason").value("risk"));

        mvc.perform(get("/api/agent/report/memory/reinforcement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reinforcementMode").value("CONSERVATIVE"));

        mvc.perform(get("/api/agent/report/gates/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sigmoidMode").value("SOFT"))
                .andExpect(jsonPath("$.citationRequireOfficial").value(true));

        mvc.perform(get("/api/agent/report/trace/kpi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentReportEventCount").value(1))
                .andExpect(jsonPath("$.boosterMode['boosterMode.active']").value("EXTREMEZ"))
                .andExpect(jsonPath("$.retrievalOrder['retrievalOrder.lastSetBy']").value("PLAN_DSL"))
                .andExpect(jsonPath("$.retrievalOrder['retrievalOrder.lastOrder'][0]").value("VECTOR"))
                .andExpect(jsonPath("$.retrievalOrder['retrieval.order.strategy.applied']").value(false))
                .andExpect(jsonPath("$.routingPlan['routing.executionPlan.primaryMode']").value("EXTREMEZ"))
                .andExpect(jsonPath("$.routingPlan['routing.executionPlan.extremeZ']").value(true))
                .andExpect(jsonPath("$.routingPlan['routing.executionPlan.overdrive']").value(false))
                .andExpect(jsonPath("$.routingPlan['specialMode.conflict.suppressed']").value("OVERDRIVE_HYPERNOVA"))
                .andExpect(jsonPath("$.hypernova['hypernova.twpmP']").value(4.0d))
                .andExpect(jsonPath("$.hypernova['hypernova.cvarPhi']").value(0.618d))
                .andExpect(jsonPath("$.hypernova['hypernova.clampApplied']").value(true))
                .andExpect(jsonPath("$.hypernova['hypernova.dppApplied']").value(true))
                .andExpect(jsonPath("$.hypernova['nova.hypernova.riskK.alloc.sum']").value(12))
                .andExpect(jsonPath("$.cihRag['cihRag.mlaBreadcrumbCount']").value(2))
                .andExpect(jsonPath("$.cihRag['cihRag.breadcrumb.queryRedacted']").value(true))
                .andExpect(jsonPath("$.matryoshka['embed.matryoshka.slice.actual']").value(4096))
                .andExpect(jsonPath("$.matryoshka['embed.matryoshka.slice.target']").value(1536))
                .andExpect(jsonPath("$.localLlm['localLlm.startup.hostHash']").value("hash:hostabcd"))
                .andExpect(jsonPath("$.localLlm['localLlm.warmup.modelHash']").value("hash:modelabcd"));
    }
}
