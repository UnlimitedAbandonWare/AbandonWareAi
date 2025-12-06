package com.abandonware.ai.stable.rag.fusion;

import java.util.*;
import com.abandonware.ai.stable.rag.model.ContextSlice;

/**
 * ScoreCalibrator:
 * - If enough samples (>=5), apply simple min-max normalization per-source.
 * - Otherwise fallback to logistic squashing f(x)=1/(1+exp(-k(x-mu))).
 */
public final class ScoreCalibrator {
    private final double k;
    public ScoreCalibrator(){ this(3.0); }
    public ScoreCalibrator(double k){ this.k = k; }

    public List<ContextSlice> apply(List<ContextSlice> in, String source){
        if(in==null || in.isEmpty()) return Collections.emptyList();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum=0;
        for(ContextSlice c: in){ double s=c.getScore(); if(s<min) min=s; if(s>max) max=s; sum+=s; }
        if(in.size()>=5 && max>min){
            double range = max-min;
            List<ContextSlice> out = new ArrayList<>(in.size());
            for(ContextSlice c: in){
                double ns = (c.getScore()-min)/range;
                out.add(c.withScore(ns));
            }
            return out;
        }else{
            double mu = sum/Math.max(1,in.size());
            List<ContextSlice> out = new ArrayList<>(in.size());
            for(ContextSlice c: in){
                double ns = 1.0/(1.0+Math.exp(-k*(c.getScore()-mu)));
                out.add(c.withScore(ns));
            }
            return out;
        }
    }
}