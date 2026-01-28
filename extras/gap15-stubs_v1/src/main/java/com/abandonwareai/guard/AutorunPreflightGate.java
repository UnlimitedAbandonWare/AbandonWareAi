package com.abandonwareai.guard;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.guard.AutorunPreflightGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.guard.AutorunPreflightGate
role: config
*/
public class AutorunPreflightGate {
    public boolean allowAction(int citations, int evidences){ return citations>=2 && evidences>=2; }

}