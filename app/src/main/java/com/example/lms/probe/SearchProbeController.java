package com.example.lms.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/probe")
@ConditionalOnProperty(prefix="probe.search", name="enabled", havingValue="true", matchIfMissing=false)
public class SearchProbeController {

  @Value("${probe.search.enabled:false}")
  private boolean enabled;

  @Value("${probe.admin-token:}")
  private String adminToken;

  private final com.example.lms.service.AnalyzeWebSearchRetriever web;
  private final com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain chain;

  public SearchProbeController(
      com.example.lms.service.AnalyzeWebSearchRetriever web,
      com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain chain) {
    this.web = web;
    this.chain = chain;
  }

  @PostMapping("/search")
  public ResponseEntity<?> search(
      @RequestHeader(name="X-Admin-Token", required=false) String token,
      @RequestBody(required=false) Map<String,Object> body) {

    if (!enabled) {
      return ResponseEntity.status(403).body(Map.of("ok", false, "reason", "Probe disabled"));
    }
    if (adminToken != null && !adminToken.isBlank()) {
      if (token == null || !adminToken.equals(token)) {
        return ResponseEntity.status(401).body(Map.of("ok", false, "reason", "Unauthorized"));
      }
    }

    String q = body != null ? String.valueOf(body.getOrDefault("q", "")) : "";
    boolean useWeb = body == null || Boolean.parseBoolean(String.valueOf(body.getOrDefault("useWeb", "true")));
    boolean useRag = body == null || Boolean.parseBoolean(String.valueOf(body.getOrDefault("useRag", "true")));
    int webTopK = 10;
    try { webTopK = body != null ? Integer.parseInt(String.valueOf(body.getOrDefault("webTopK","10"))) : 10; } catch (Exception ignore){}

    if (!useWeb && !useRag) {
      return ResponseEntity.ok(Map.of("ok", true, "result", List.of(), "notice", "Both useWeb and useRag are false"));
    }
    List<?> res = useRag ? chain.retrieve(q) : web.search(q, webTopK);
    return ResponseEntity.ok(Map.of("ok", true, "result", res));
  }
}
