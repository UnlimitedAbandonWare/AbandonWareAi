package com.abandonware.ai.agent.integrations.service.plan;


import java.util.*;
/**
 * Generates 3 sub-questions (BQ/ER/RC templates).
 * Fail-soft: falls back to original query if inputs are short.
 */
public class SubQuestionGenerator {
    public List<String> generate(String query){
        if (query == null || query.trim().length() < 4){
            return Arrays.asList(query, query, query);
        }
        String q = query.trim();
        return Arrays.asList(
            "BQ: 핵심 배경/정의 - " + q,
            "ER: 최신/증거 중심 - " + q,
            "RC: 반례/대안 - " + q
        );
    }
}