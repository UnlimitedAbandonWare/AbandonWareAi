package com.abandonware.ai.service.onnx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.onnx.OnnxInputGuard
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.onnx.OnnxInputGuard
role: config
*/
public class OnnxInputGuard {
  @Value("${onnx.max-chars:8192}") int maxChars;
  public boolean allow(String query, List<String> docs) {
    if (query == null) return false;
    int total = query.length();
    if (docs != null) {
      for (String d : docs) { total += (d == null ? 0 : d.length()); }
    }
    return total <= maxChars;
  }
}