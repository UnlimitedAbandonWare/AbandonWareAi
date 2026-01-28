package com.example.lms.service.rag.planner;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.planner.SelfAskPlanner
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.planner.SelfAskPlanner
role: config
*/
public class SelfAskPlanner {
    public List<String> generateSubQuestions(String q){
        if (q == null || q.isBlank()) return List.of();
        List<String> subs = new ArrayList<>();
        subs.add("정의/배경: " + q);
        subs.add("동의어/별칭: " + q + " 는 또 무엇으로 불리는가?");
        subs.add("관계/영향: " + q + " 와(과) 관련된 핵심 관계는?");
        return subs;
    }
}