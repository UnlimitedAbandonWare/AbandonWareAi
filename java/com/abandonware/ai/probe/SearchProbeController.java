package com.abandonware.ai.probe;

import com.abandonware.ai.service.rag.AnalyzeWebSearchRetriever;
import com.abandonware.ai.service.rag.handler.DynamicRetrievalHandlerChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/probe")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="probe.search.enabled", havingValue="true")
public class SearchProbeController {

    @Value("${probe.search.enabled:false}")
    private boolean enabled;

    @Value("${probe.admin-token:}")
    private String adminToken;

    private final AnalyzeWebSearchRetriever web;
    private final DynamicRetrievalHandlerChain chain;

    public SearchProbeController(AnalyzeWebSearchRetriever web,
                                 DynamicRetrievalHandlerChain chain) {
        this.web = web;
        this.chain = chain;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody(required = false) Map<String,Object> body,
                                    @RequestHeader(value="X-Admin-Token", required=false) String headerToken){
        if (!enabled) return ResponseEntity.status(403).body("Probe disabled");
        String token = headerToken == null ? "" : headerToken;
        if (!adminToken.isBlank() && !adminToken.equals(token)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        String q = body != null && body.get("q") != null ? String.valueOf(body.get("q")) : "";
        boolean useWeb = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("useWeb", "true")));
        boolean useRag = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("useRag", "false")));
        int webTopK = 8;
        try {
            webTopK = body != null ? Integer.parseInt(String.valueOf(body.getOrDefault("webTopK", "8"))) : 8;
        } catch (Exception ignore) {}

        if (!useWeb && !useRag) {
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "result", List.of(),
                "notice", "Both useWeb and useRag are false"
            ));
        }
        List<?> res = useRag ? chain.retrieve(q) : web.search(q, webTopK);
        return ResponseEntity.ok(Map.of("ok", true, "result", res));
    }
}