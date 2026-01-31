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
package com.example.lms.addons.budget;

public record TimeBudget(long totalMs, long webMs, long vectorMs, long rerankMs) {
    public static TimeBudget of(long total, long web, long vector, long rerank){
        return new TimeBudget(total, web, vector, rerank);
    }
}