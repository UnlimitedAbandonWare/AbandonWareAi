package com.abandonware.ai.example.lms.cfvm;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.example.lms.cfvm.Stage
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.example.lms.cfvm.Stage
role: config
*/
public enum Stage { ANALYZE, SELF_ASK, WEB, VECTOR, KG, ANSWER, BUILD, RUNTIME }
}