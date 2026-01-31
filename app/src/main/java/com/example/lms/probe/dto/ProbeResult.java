package com.example.lms.probe.dto;

import java.util.List;
import java.util.Map;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.probe.dto.ProbeResult
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.probe.dto.ProbeResult
role: config
*/
public class ProbeResult {
    public String query;
    public String normalized;
    public Map<String, Object> metaEcho;
    public int total;
    public Map<String, Long> byDomain;
    public long financeNoiseCount;
    public List<ProbeDoc> docs;
}