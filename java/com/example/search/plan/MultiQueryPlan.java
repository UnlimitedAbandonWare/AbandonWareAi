package com.example.search.plan;

import java.util.*;



public record MultiQueryPlan(List<String> variants, double confidence, Map<String,Object> hints) {
    public static MultiQueryPlan of(List<String> v){
        return new MultiQueryPlan(Collections.unmodifiableList(v), 0.5, new HashMap<>());
    }
}