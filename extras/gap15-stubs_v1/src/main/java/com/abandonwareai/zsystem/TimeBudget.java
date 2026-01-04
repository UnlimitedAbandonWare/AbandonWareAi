package com.abandonwareai.zsystem;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.zsystem.TimeBudget
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonwareai.zsystem.TimeBudget
role: config
*/
public class TimeBudget {
    private long millis=3500; public long millis(){ return millis; }

}