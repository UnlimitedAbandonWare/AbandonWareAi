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
package com.abandonware.ai.addons.synthesis;

import java.util.Map;



public record ContextItem(
        String id, String title, String snippet, String source, double score, int rank,
        Map<String, Object> meta
) {}