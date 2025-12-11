package com.example.lms.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Light MoE router that selects top-K experts from {web,vector,kg,bm25}.
 */
public class ExpertRouter {

    public List<String> select(String query, double qcScore, int k) {
        List<String> experts = new ArrayList<>();
        if (k <= 0) k = 2;
        if (query != null && query.toLowerCase().contains("site:")) {
            experts.add("web");
            if (experts.size()<k) experts.add("vector");
        } else if (qcScore >= 0.66) {
            experts.add("vector");
            if (experts.size()<k) experts.add("web");
        } else {
            experts.add("web");
            if (experts.size()<k) experts.add("bm25");
        }
        if (experts.size()<k) experts.add("kg");
        return experts.subList(0, Math.min(k, experts.size()));
    }
}