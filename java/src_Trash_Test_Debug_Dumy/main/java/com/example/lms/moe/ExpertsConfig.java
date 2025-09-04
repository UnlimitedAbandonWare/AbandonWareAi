package com.example.lms.moe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * experts.yml을 직렬화할 때 사용되는 설정 클래스입니다. 전문가 목록과 게이트/블렌딩 파라미터를 포함합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExpertsConfig {
    /** Optional schema metadata for forward‑compat */
    public Integer version = 1;
    public String  updated;
    public List<ExpertDefinition> experts = new ArrayList<>();
    public Gate gate = new Gate();
    public Blend blend = new Blend();

    public static class Gate {
        public double alias_hits_weight = 0.6;
        public double token_overlap_weight = 0.3;
        public double recency_weight = 0.1;
        public double min_route_score = 0.25;
    }

    public static class Blend {
        public int rrf_k = 60;
        public double expert_weight_cap = 2.0;
        public String normalize = "max";
    }
}