package com.nova.protocol.alloc;
import com.example.lms.search.TraceStore;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SimpleRiskKAllocator implements RiskKAllocator {
    private static final double RISK_PENALTY = 4.0d;

    @Override
    public int[] alloc(double[] logits, double[] risk, int totalK, double temp, int[] floor) {
        int budget = Math.max(0, totalK);
        if (logits == null || logits.length == 0) {
            int[] out = new int[]{budget,0,0};
            traceRiskK(out, false, budget, budget > 0 ? 1.0d : 0.0d, 0.0d);
            return out;
        }
        int n = logits.length;
        int[] ks = normalizedFloors(floor, n, budget);
        int floorsum = sum(ks);
        int remain = Math.max(0, budget - floorsum);
        double[] adjustedLogits = new double[n];
        for (int i=0;i<n;i++) {
            adjustedLogits[i] = finite(logits[i]) - (RISK_PENALTY * riskAt(risk, i));
        }
        double max = adjustedLogits[0];
        for (int i=1;i<n;i++) if (adjustedLogits[i] > max) max = adjustedLogits[i];
        double[] exps = new double[n];
        double sum = 0.0;
        for (int i=0;i<n;i++){ 
            double v = (adjustedLogits[i]-max) / Math.max(1e-6, finite(temp));
            exps[i] = Math.exp(v);
            sum += exps[i];
        }
        double[] probabilities = new double[n];
        for (int i = 0; i < n; i++) {
            probabilities[i] = sum <= 0.0d ? 0.0d : exps[i] / sum;
        }
        int[] add = new int[n];
        double[] frac = new double[n];
        int allocated = 0;
        for (int i=0;i<n;i++){
            double exact = remain * (exps[i]/sum);
            add[i] = (int)Math.floor(exact);
            frac[i] = exact - add[i];
            allocated += add[i];
        }
        int left = remain - allocated;
        while (left > 0){
            int idx = maxFractionIndex(frac);
            add[idx] += 1;
            frac[idx] = -1.0d;
            left -= 1;
        }
        for (int i=0;i<n;i++) ks[i] += add[i];
        normalizeTotal(ks, budget);
        traceRiskK(ks, true, budget, probabilityAt(probabilities, 0), probabilityAt(probabilities, 1));
        return ks;
    }

    private static int[] normalizedFloors(int[] floor, int n, int budget) {
        int[] out = new int[n];
        if (floor == null || floor.length == 0 || budget <= 0) {
            return out;
        }
        long floorSum = 0L;
        for (int i = 0; i < n; i++) {
            int value = i < floor.length ? Math.max(0, floor[i]) : 0;
            out[i] = value;
            floorSum += value;
        }
        if (floorSum <= budget) {
            return out;
        }

        int allocated = 0;
        double[] frac = new double[n];
        for (int i = 0; i < n; i++) {
            double exact = (budget * (double) out[i]) / (double) floorSum;
            int capped = (int) Math.floor(exact);
            out[i] = capped;
            frac[i] = exact - capped;
            allocated += capped;
        }
        int left = budget - allocated;
        while (left > 0) {
            int idx = maxFractionIndex(frac);
            out[idx] += 1;
            frac[idx] = -1.0d;
            left -= 1;
        }
        return out;
    }

    private static void normalizeTotal(int[] ks, int budget) {
        if (ks == null || ks.length == 0) {
            return;
        }
        int diff = budget - sum(ks);
        if (diff > 0) {
            ks[0] += diff;
            return;
        }
        int excess = -diff;
        for (int i = ks.length - 1; i >= 0 && excess > 0; i--) {
            int take = Math.min(ks[i], excess);
            ks[i] -= take;
            excess -= take;
        }
    }

    private static int sum(int[] values) {
        int total = 0;
        if (values != null) {
            for (int value : values) {
                total += Math.max(0, value);
            }
        }
        return total;
    }

    private static double riskAt(double[] risk, int index) {
        if (risk == null || index >= risk.length) {
            return 0.0d;
        }
        double value = finite(risk[index]);
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0d;
    }

    private static void traceRiskK(int[] ks, boolean used, int totalK, double webScore, double vectorScore) {
        TraceStore.put("hypernova.riskK.webK", laneK(ks, 0));
        TraceStore.put("hypernova.riskK.vectorK", laneK(ks, 1));
        TraceStore.put("hypernova.riskK.softmax.webScore", finite(webScore));
        TraceStore.put("hypernova.riskK.softmax.vectorScore", finite(vectorScore));
        TraceStore.put("hypernova.riskKAlloc", Map.of(
                "used", used,
                "totalK", Math.max(0, totalK),
                "sum", sum(ks),
                "webK", laneK(ks, 0),
                "vectorK", laneK(ks, 1),
                "kgK", laneK(ks, 2)
        ));
    }

    private static int laneK(int[] ks, int index) {
        if (ks == null || index < 0 || index >= ks.length) {
            return 0;
        }
        return Math.max(0, ks[index]);
    }

    private static double probabilityAt(double[] probabilities, int index) {
        if (probabilities == null || index < 0 || index >= probabilities.length) {
            return 0.0d;
        }
        double value = finite(probabilities[index]);
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static int maxFractionIndex(double[] frac) {
        int best = 0;
        double bestValue = frac == null || frac.length == 0 ? 0.0d : frac[0];
        for (int i=1;i<frac.length;i++) {
            if (frac[i] > bestValue) {
                bestValue = frac[i];
                best = i;
            }
        }
        return best;
    }
}
