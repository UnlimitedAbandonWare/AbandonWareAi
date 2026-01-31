
package com.example.moe;

import org.junit.jupiter.api.Test;
import java.util.*;


import static org.junit.jupiter.api.Assertions.*;

public class MultiSourceMoETest {

    @Test
    public void testGateSumToOne() {
        MultiSourceMoE moe = new MultiSourceMoE();
        moe.tau = 0.7; moe.topK = null;

        double[][] Q = {{1.0,0.0},{0.0,1.0}};
        double[][] H = {{0,0},{0,0}};

        FeatureCollector.Features f1 = new FeatureCollector.Features();
        f1.authority = 1.0; f1.novelty = 0.9; f1.Fd = 1.0; f1.match = 0.2;
        FeatureCollector.Features f2 = new FeatureCollector.Features();
        f2.authority = 0.5; f2.novelty = 0.2; f2.Fd = 1.2; f2.match = 0.1;

        double[][] K1 = {{0.1,0.2},{0.0,0.1}};
        double[][] V1 = {{0.2,0.1},{-0.1,0.0}};
        double[][] W1 = {{1,0},{0,1}};
        double[][] K2 = {{-0.2,0.1},{0.3,-0.1}};
        double[][] V2 = {{0.1,0.3},{0.2,0.2}};
        double[][] W2 = {{1,0},{0,1}};

        List<MultiSourceMoE.Source> S = List.of(
            new MultiSourceMoE.Source(K1,V1,W1,f1),
            new MultiSourceMoE.Source(K2,V2,W2,f2)
        );
        MultiSourceMoE.Output out = moe.forward(Q, H, S);

        double sum = 0.0; for (double v : out.gates) sum += v;
        assertEquals(1.0, sum, 1e-6);
    }

    @Test
    public void testTopKMasking() {
        MultiSourceMoE moe = new MultiSourceMoE();
        moe.topK = 1; moe.tau = 0.5;

        double[][] Q = {{0.1,0.2,0.3,0.4}};
        double[][] H = {{0,0,0,0}};

        FeatureCollector.Features f1 = new FeatureCollector.Features(); f1.authority=1.0; f1.novelty=0.9; f1.Fd=1.0; f1.match=0.5;
        FeatureCollector.Features f2 = new FeatureCollector.Features(); f2.authority=0.8; f2.novelty=0.2; f2.Fd=1.2; f2.match=0.1;
        FeatureCollector.Features f3 = new FeatureCollector.Features(); f3.authority=0.6; f3.novelty=0.7; f3.Fd=0.9; f3.match=0.2;

        double[][] K = {{0.1,0.2,0.1,0.0}};
        double[][] V = {{0.0,0.1,0.0,0.1}};
        double[][] W = {{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}};

        List<MultiSourceMoE.Source> S = List.of(
            new MultiSourceMoE.Source(K,V,W,f1),
            new MultiSourceMoE.Source(K,V,W,f2),
            new MultiSourceMoE.Source(K,V,W,f3)
        );

        MultiSourceMoE.Output out = moe.forward(Q, H, S);
        int zeros = 0; for (double g : out.gates) if (g < 1e-9) zeros++;
        assertTrue(zeros >= 2, "Top-1 should zero out all but one gate");
    }

    @Test
    public void testCollectorMapping() {
        FeatureCollector fc = new FeatureCollector();
        Map<String,Object> meta = new HashMap<>();
        meta.put("baseWeight", 0.7);
        meta.put("noveltyFactor", 0.85); // -> u ≈ 0.7
        meta.put("F", 1.3);
        meta.put("alignmentScore", 0.4);
        meta.put("recentness", 0.6);
        FeatureCollector.Features f = fc.collect(meta);
        assertEquals(0.7, f.authority, 1e-9);
        assertEquals(0.7, f.novelty, 1e-9);
        assertEquals(1.3, f.Fd, 1e-9);
        assertEquals(0.4, f.match, 1e-9);
        assertEquals(1, f.extras.length);
    }
}