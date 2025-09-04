package com.example.lms.moe;

import java.util.List;

/**
 * MOE 전문가 정의. 각 전문가의 식별자, 태그, 인덱스명, 웹 정책과 가중치 설정을 포함합니다.
 */
public class ExpertDefinition {
    public String id;
    public String name;
    public List<String> tags;
    public String index;
    public WebPolicy web = new WebPolicy();
    public Weights weights = new Weights();

    public static class WebPolicy {
        public List<String> domain_allow = List.of();
        public List<String> domain_deny = List.of();
    }
    public static class Weights {
        public Chain chain = new Chain();
        public static class Chain {
            public double self_ask = 1.2;
            public double web = 1.0;
            public double vector = 1.5;
        }
    }
}