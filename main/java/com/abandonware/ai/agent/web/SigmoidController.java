package com.abandonware.ai.agent.web;

import com.abandonware.ai.agent.nn.SigmoidOrchestrator;
import com.abandonware.ai.agent.nn.EnsembleEvaluator;
import org.springframework.web.bind.annotation.*;
import java.util.*;



@RestController
@RequestMapping("/internal/nn")
public class SigmoidController {

    public static class DemoReq {
        public double[] features;
        public double[] weights;
        public boolean finger = true;
        public double alpha = 0.25;
        public double baseline = 0.05;
        public Double k0;
        public Double x0;
        public Integer steps;
        public List<Double> externalScores;
        public Long seed;
    }

    @PostMapping("/sigmoid-demo")
    public Map<String,Object> demo(@RequestBody DemoReq req){
        DemoReq safeReq = req == null ? new DemoReq() : req;
        SigmoidOrchestrator orch = new SigmoidOrchestrator();
        double k0 = safeReq.k0 == null ? 1.0 : safeReq.k0;
        double x00 = safeReq.x0 == null ? 0.0 : safeReq.x0;
        int steps = safeReq.steps == null ? 250 : Math.max(1, safeReq.steps);
        List<Double> ext = (safeReq.externalScores==null || safeReq.externalScores.isEmpty())
                ? Arrays.asList(0.45,0.5,0.49,0.52,0.47) : safeReq.externalScores;
        long seed = safeReq.seed == null ? 42L : safeReq.seed;

        SigmoidOrchestrator.Result r = orch.search(
                safeReq.features==null? new double[]{0.2,0.5,0.8} : safeReq.features,
                safeReq.weights==null? new double[]{1,1,1} : safeReq.weights,
                safeReq.finger, safeReq.alpha,
                safeReq.baseline, ext, k0, x00, steps, seed
        );

        EnsembleEvaluator.Stats st = EnsembleEvaluator.stats(ext);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("kBest", r.kBest);
        out.put("x0Best", r.x0Best);
        out.put("combined", r.sCombined);
        out.put("improvementFactor", r.improvementFactor);
        out.put("pass9x", r.pass9x);
        out.put("kHistory", r.kxHistory);
        out.put("ensemble", Map.of(
                "min", st.min, "max", st.max, "mean", st.mean, "std", st.std, "zscore", st.zscore
        ));
        return out;
    }
}
