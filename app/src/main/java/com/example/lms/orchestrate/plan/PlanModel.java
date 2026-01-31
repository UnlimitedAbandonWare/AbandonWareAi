/**
//* [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
//* Module: Unknown
//* Role: class
//* Thread-Safety: appears stateless.
//*/
/* agent-hint:
id: Unknown
role: class
//*/
package com.example.lms.orchestrate.plan;

import java.util.Map;

public record PlanModel(String id,
                        Map<String,Object> k,
                        Map<String,Boolean> enable,
                        Map<String,Object> thresholds,
                        Map<String,Object> gates) { }