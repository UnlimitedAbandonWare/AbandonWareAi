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
package com.abandonware.ai.addons.complexity;

import java.util.Map;



public record ComplexityResult(
        ComplexityTag tag,
        double confidence,
        Map<String, Object> features
) {}