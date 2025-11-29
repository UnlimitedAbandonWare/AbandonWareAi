package com.abandonware.ai.probe;

import trace.TraceContext;
import com.abandonware.ai.probe.dto.SearchProbeRequest;
import java.util.Map;
import java.util.HashMap;

public class SearchProbeService {
  public Map<String,Object> run(SearchProbeRequest req) {
    Map<String,Object> out = new HashMap<>();
    out.put("echo", req.intent);
    out.put("webTopK", req.webTopK==null?10:req.webTopK);
    out.put("used", Map.of("web", req.useWeb, "rag", req.useRag));
    out.put("officialOnly", req.officialSourcesOnly);
    return out;
  }
}