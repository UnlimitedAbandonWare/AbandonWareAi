package com.abandonware.ai.service.onnx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
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