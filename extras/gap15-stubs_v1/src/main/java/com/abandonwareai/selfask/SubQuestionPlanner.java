package com.abandonwareai.selfask;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.selfask.SubQuestionPlanner
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.selfask.SubQuestionPlanner
role: config
*/
public class SubQuestionPlanner {
    public String[] branch3(String q){ return new String[]{q+" (BQ)", q+" (ER)", q+" (RC)"}; }

}