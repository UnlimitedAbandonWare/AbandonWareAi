package com.abandonwareai.zerobreak.controller;

import com.abandonwareai.zerobreak.gate.CitationGate;
import com.abandonwareai.zerobreak.gate.FinalSigmoidGate;
import com.abandonwareai.zerobreak.strategy.PlannerNexus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/zerobreak")
public class ZeroBreakAdminController {
    private final PlannerNexus planner = new PlannerNexus("ops/zerobreak/plans");

    @GetMapping(value="/plan", produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> listPlans() {
        return Map.of("plans", List.of("safe_autorun.v1","brave.v1","zero_break.v1"));
    }
    @GetMapping(value="/plan/{id}", produces=MediaType.TEXT_PLAIN_VALUE)
    public String getPlan(@PathVariable("id") String id) throws IOException {
        return planner.loadPlanYaml(id);
    }
    @PostMapping(value="/dry-run", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> dryRun(@RequestBody Map<String,Object> payload) {
        int minCite = ((Number)payload.getOrDefault("minCitations", 3)).intValue();
        double fuseScore = ((Number)payload.getOrDefault("fuseScore", 0.7)).doubleValue();
        double k = ((Number)payload.getOrDefault("k", 10.0)).doubleValue();
        double x0 = ((Number)payload.getOrDefault("x0", 0.65)).doubleValue();

        CitationGate cg = new CitationGate();
        boolean citesOk = cg.validate((List<String>)payload.getOrDefault("citations", List.of()), minCite);
        FinalSigmoidGate sg = new FinalSigmoidGate(0.90, k, x0);
        boolean approved = citesOk && sg.approve(fuseScore);
        return Map.of("citationsOk", citesOk, "sigmoidApproved", approved, "approved", approved);
    }
}