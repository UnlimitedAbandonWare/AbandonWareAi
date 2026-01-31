package com.example.lms.probe.dto;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.probe.dto.ProbeRequest
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.probe.dto.ProbeRequest
role: config
*/
public class ProbeRequest {
    public String query;
    public boolean useWeb = true;
    public boolean useRag = true;
    public int webTopK = 8;
}