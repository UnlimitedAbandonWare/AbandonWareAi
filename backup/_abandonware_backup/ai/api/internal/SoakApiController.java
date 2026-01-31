package com.abandonware.ai.api.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.abandonware.ai.service.soak.SoakTestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/soak")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.api.internal.SoakApiController
 * Role: controller
 * Key Endpoints: GET /internal/soak/run, ANY /internal/soak/internal/soak
 * Dependencies: com.abandonware.ai.service.soak.SoakTestService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.api.internal.SoakApiController
role: controller
api:
  - GET /internal/soak/run
  - ANY /internal/soak/internal/soak
*/
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakApiController {
    private final SoakTestService service;
    public SoakApiController(SoakTestService service) { this.service = service; }

    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "10") int k,
                                   @RequestParam(defaultValue = "default") String topic) {
        return service.run(k, topic);
    }
}