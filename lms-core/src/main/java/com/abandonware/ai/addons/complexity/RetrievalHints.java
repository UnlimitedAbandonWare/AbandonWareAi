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


public record RetrievalHints(
        int webTopK,
        int vectorTopK,
        boolean useCrossEncoder,
        boolean useBiEncoder,
        boolean enable2Pass,
        boolean enableWeb,
        String routingProfile
) {
    public static RetrievalHints simple() {
        return new RetrievalHints(0, 8, false, true, false, false, "default");
    }
}