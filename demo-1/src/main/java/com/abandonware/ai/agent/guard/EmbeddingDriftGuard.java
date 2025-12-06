package com.abandonware.ai.agent.guard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "features.EmbeddingDriftGuard", havingValue = "true", matchIfMissing = true)
public class EmbeddingDriftGuard {
    public static class Stats {
        public double mean; public double variance;
        public Stats(double mean, double variance) {this.mean=mean; this.variance=variance;}
    }
    // Placeholder methods; wire to real vector store stats later
    public Stats computeStats(List<double[]> vectors) {
        if (vectors==null || vectors.isEmpty()) return new Stats(0,0);
        int dim = vectors.get(0).length;
        double[] mean = new double[dim];
        for (double[] v: vectors) for (int i=0;i<dim;i++) mean[i]+=v[i];
        for (int i=0;i<dim;i++) mean[i]/=vectors.size();
        double var=0;
        for (double[] v: vectors) for (int i=0;i<dim;i++) {
            double d=v[i]-mean[i]; var += d*d;
        }
        var /= (vectors.size()*dim);
        return new Stats(0, var);
    }
}
