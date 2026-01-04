package com.example.lms.service.rag.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CvarAggregator {
    public static double tailMean(List<Double> scores, double alpha){
        if (scores == null || scores.isEmpty()) return 0.0;
        List<Double> s = new ArrayList<>(scores);
        Collections.sort(s);
        int n = Math.max(1, (int)Math.ceil(Math.max(0.0, Math.min(1.0, alpha)) * s.size()));
        double sum = 0.0;
        for (int i = s.size() - n; i < s.size(); i++) sum += s.get(i);
        return sum / n;
    }
}