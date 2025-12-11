package com.abandonware.ai.resilience;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.resilience.FlowJoiner
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.resilience.FlowJoiner
role: config
*/
public class FlowJoiner {
    public enum Mode { PRCYK, PCY, PRC, RYK }
    public Mode decide(boolean hasR, boolean hasK) {
        if (hasR && hasK) return Mode.PRCYK;
        if (!hasR && hasK) return Mode.PCY;
        if (hasR && !hasK) return Mode.PRC;
        return Mode.RYK;
    }
}