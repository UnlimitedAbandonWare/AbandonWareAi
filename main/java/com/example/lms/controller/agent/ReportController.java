package com.example.lms.controller.agent;

import com.example.lms.dto.agent.CfvmSnapshotDto;
import com.example.lms.dto.agent.GatesSummaryDto;
import com.example.lms.dto.agent.HypernovaFusionDto;
import com.example.lms.dto.agent.MoeDecisionDto;
import com.example.lms.dto.agent.OverdriveStatusDto;
import com.example.lms.service.agent.ReportSnapshotService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(value = "/api/agent/report", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportSnapshotService service;

    public ReportController(ReportSnapshotService service) {
        this.service = service;
    }

    @GetMapping("/cfvm/snapshot")
    public CfvmSnapshotDto cfvmSnapshot() {
        return service.cfvmSnapshot();
    }

    @GetMapping("/moe/decision")
    public MoeDecisionDto moeDecision() {
        return service.moeDecision();
    }

    @GetMapping("/artplate/status")
    public Map<String, Object> artplateStatus() {
        return service.artplateStatus();
    }

    @GetMapping("/overdrive/status")
    public OverdriveStatusDto overdriveStatus() {
        return service.overdriveStatus();
    }

    @GetMapping("/hypernova/fusion")
    public HypernovaFusionDto hypernovaFusion() {
        return service.hypernovaFusion();
    }

    @GetMapping("/extremez/status")
    public Map<String, Object> extremezStatus() {
        return service.extremezStatus();
    }

    @GetMapping("/memory/reinforcement")
    public Map<String, Object> memoryReinforcement() {
        return service.memoryReinforcement();
    }

    @GetMapping("/gates/summary")
    public GatesSummaryDto gatesSummary() {
        return service.gatesSummary();
    }

    @GetMapping("/trace/kpi")
    public Map<String, Object> traceKpi() {
        return service.traceKpi();
    }
}
