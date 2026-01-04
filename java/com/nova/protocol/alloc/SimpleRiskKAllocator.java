package com.nova.protocol.alloc;
import org.springframework.stereotype.Component;

@Component
public class SimpleRiskKAllocator implements RiskKAllocator {
    @Override
    public int[] alloc(double[] logits, double[] risk, int totalK, double temp, int[] floor) {
        if (logits == null || logits.length == 0) return new int[]{totalK,0,0};
        int n = logits.length;
        int[] ks = new int[n];
        int floorsum = 0;
        for (int i=0;i<n;i++){ ks[i] = (floor!=null && i<floor.length)? Math.max(0, floor[i]) : 0; floorsum += ks[i]; }
        int remain = Math.max(0, totalK - floorsum);
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
        int left = remain - allocated;
        int idx = 0;
        while (left > 0){
            add[idx % n] += 1;
            left -= 1;
            idx += 1;
        }
        for (int i=0;i<n;i++) ks[i] += add[i];
        int s=0; for(int v: ks) s+=v;
        if (s != totalK) {
            int diff = totalK - s;
            ks[0] += diff;
        }
        return ks;
    }
}