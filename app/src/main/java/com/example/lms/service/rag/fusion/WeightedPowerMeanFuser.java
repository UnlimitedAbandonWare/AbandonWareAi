package com.example.lms.service.rag.fusion;

import java.util.*;
public class WeightedPowerMeanFuser {
    public double fuse(List<Double> scores, double p, List<Double> weights){
        if (scores==null || scores.isEmpty()) return 0.0;
        int n = scores.size();
        if (weights==null || weights.size()!=n) {
            weights = new ArrayList<>(Collections.nCopies(n, 1.0));
        }
        double num=0.0, den=0.0;
        for (int i=0;i<n;i++){
            double w = weights.get(i);
            den += w;
            if (p==0.0){
                num += w * Math.log(Math.max(1e-9, scores.get(i)));
            } else {
                num += w * Math.pow(scores.get(i), p);
            }
        }
        if (p==0.0){
            return Math.exp(num/den);
        } else {
            return Math.pow(num/den, 1.0/p);
        }
    }
}