package com.abandonwareai.planner;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.planner.KAllocationPolicy
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.planner.KAllocationPolicy
role: config
flags: [kg]
*/
public class KAllocationPolicy {
    public int webK(){return 12;} public int vectorK(){return 6;} public int kgK(){return 4;}

}