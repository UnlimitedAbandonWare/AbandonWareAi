package com.abandonware.ai.service;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.IntentRouter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.IntentRouter
role: config
*/
public class IntentRouter {
  public String resolveTopic(String query) {
    if (query==null) return "default";
    String q = query.toLowerCase();
    if (q.contains("stock") || q.contains("revenue") || q.contains("finance")) return "finance";
    return "default";
  }
}