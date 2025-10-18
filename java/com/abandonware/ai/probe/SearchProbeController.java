package com.abandonware.ai.probe;

import com.abandonware.ai.service.rag.AnalyzeWebSearchRetriever;
import com.abandonware.ai.service.rag.handler.DynamicRetrievalHandlerChain;
import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/probe")
public class SearchProbeController {

    private final DynamicRetrievalHandlerChain chain;
    private final AnalyzeWebSearchRetriever web;
    private final boolean enabled;
    private final String adminToken;

    public SearchProbeController(DynamicRetrievalHandlerChain chain,
                                 AnalyzeWebSearchRetriever web,
                                 @Value("${probe.search.enabled:true}") boolean enabled,
                                 @Value("${probe.admin-token:}") String adminToken) {
        this.chain = chain;
        this.web = web;
        this.enabled = enabled;
        this.adminToken = adminToken;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestHeader(value="X-Admin-Token", required=false) String token,
                                    @RequestParam(defaultValue = "true") boolean useWeb,
                                    @RequestParam(defaultValue = "true") boolean useRag,
                                    @RequestParam(defaultValue = "8") int webTopK,
                                    @RequestParam(defaultValue = "") String q) {
        if (!enabled) return ResponseEntity.status(403).body("Probe disabled");
        if (adminToken == null || adminToken.isBlank() || token == null || !token.equals(adminToken)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        List<ContextSlice> res = useRag ? chain.retrieve(q) : web.search(q, webTopK);
        return ResponseEntity.ok(res);
    }
}
