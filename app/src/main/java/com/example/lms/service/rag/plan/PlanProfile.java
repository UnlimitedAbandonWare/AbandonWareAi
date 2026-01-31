package com.example.lms.service.rag.plan;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.plan.PlanProfile
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.service.rag.plan.PlanProfile
role: config
*/
public class PlanProfile {
    public java.util.List<String> order;
    public java.util.Map<String,Integer> k;
    public java.util.Map<String,Integer> timeouts;
    public java.util.Map<String,String> gates;
}