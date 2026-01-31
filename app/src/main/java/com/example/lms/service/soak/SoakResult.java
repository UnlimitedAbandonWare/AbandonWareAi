package com.example.lms.service.soak;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Map;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.soak.SoakResult
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.soak.SoakResult
role: config
*/
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakResult { public Map<String,Object> data; }