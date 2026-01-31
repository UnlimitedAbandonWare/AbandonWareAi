package com.nova.protocol.alloc;
import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.alloc.SimpleRiskKAllocator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.alloc.SimpleRiskKAllocator
role: config
*/
public class SimpleRiskKAllocator implements RiskKAllocator {
    @Override
    public int[] alloc(double[] logits, double[] risk, int totalK, double temp, int[] floor) {
        if (logits == null || logits.length == 0) return new int[]{totalK,0,0};
        int n = logits.length;
        int[] ks = new int[n];
        int floorsum = 0;
        for (int i=0;i<n;i++){ ks[i] = (floor!=null && i<floor.length)? Math.max(0, floor[i]) : 0; floorsum += ks[i]; }
        int remain = Math.max(0, totalK - floorsum);
        // softmax with temperature
        double max = logits[0];
        for (int i=1;i<n;i++) if (logits[i] > max) max = logits[i];
        double[] exps = new double[n];
        double sum = 0.0;
        for (int i=0;i<n;i++){ 
            double v = (logits[i]-max) / Math.max(1e-6, temp);
            exps[i] = Math.exp(v);
            sum += exps[i];
        }
        int[] add = new int[n];
        int allocated = 0;
        for (int i=0;i<n;i++){
            add[i] = (int)Math.floor(remain * (exps[i]/sum));
            allocated += add[i];
        }
        // distribute leftovers
        int left = remain - allocated;
        int idx = 0;
        while (left > 0){
            add[idx % n] += 1;
            left -= 1;
            idx += 1;
        }
        for (int i=0;i<n;i++) ks[i] += add[i];
        // ensure sum == totalK
        int s=0; for(int v: ks) s+=v;
        if (s != totalK) {
            int diff = totalK - s;
            ks[0] += diff;
        }
        return ks;
    }
}