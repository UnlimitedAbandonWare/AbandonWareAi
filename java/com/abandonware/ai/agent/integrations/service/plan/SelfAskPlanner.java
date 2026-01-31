package com.abandonware.ai.agent.integrations.service.plan;


import java.util.*;
/**
 * SelfAskPlanner: fan-out 3-way, merge with simple RRF for now.
 */
public class SelfAskPlanner {
    public List<String> plan(String query){
        SubQuestionGenerator gen = new SubQuestionGenerator();
        List<String> subs = gen.generate(query);
        // Placeholder: upstream handlers handle retrieval; here we just return the sub-queries.
        return subs;
    }
}