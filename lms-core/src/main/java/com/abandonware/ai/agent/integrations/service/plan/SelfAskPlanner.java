package com.abandonware.ai.agent.integrations.service.plan;


import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.plan.SelfAskPlanner
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.plan.SelfAskPlanner
role: config
*/
public class SelfAskPlanner {
    public List<String> plan(String query){
        SubQuestionGenerator gen = new SubQuestionGenerator();
        List<String> subs = gen.generate(query);
        // Placeholder: upstream handlers handle retrieval; here we just return the sub-queries.
        return subs;
    }
}