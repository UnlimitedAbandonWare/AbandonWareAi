package com.abandonware.ai.agent.integrations.service.plan;


import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.plan.SubQuestionGenerator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.plan.SubQuestionGenerator
role: config
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