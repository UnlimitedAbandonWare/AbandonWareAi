package com.abandonware.ai.service;

public class IntentRouter {
  public String resolveTopic(String query) {
    if (query==null) return "default";
    String q = query.toLowerCase();
    if (q.contains("stock") || q.contains("revenue") || q.contains("finance")) return "finance";
    return "default";
  }
}