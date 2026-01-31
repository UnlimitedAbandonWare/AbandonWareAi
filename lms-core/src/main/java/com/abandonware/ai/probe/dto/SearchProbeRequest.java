package com.abandonware.ai.probe.dto;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.probe.dto.SearchProbeRequest
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.probe.dto.SearchProbeRequest
role: config
*/
public class SearchProbeRequest {
  public boolean useWeb = true;
  public boolean useRag = true;
  public boolean officialSourcesOnly = false;
  public Integer webTopK;
  public String intent;
  public String query;
}