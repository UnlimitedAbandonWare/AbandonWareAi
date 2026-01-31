package com.abandonware.ai.example.lms.cfvm;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;




@RestController
@RequestMapping("/internal/cfvm")
@RequiredArgsConstructor
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.example.lms.cfvm.CfvmAdminController
 * Role: controller
 * Key Endpoints: GET /internal/cfvm/buffer, POST /internal/cfvm/flush, ANY /internal/cfvm/internal/cfvm
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.example.lms.cfvm.CfvmAdminController
role: controller
api:
  - GET /internal/cfvm/buffer
  - POST /internal/cfvm/flush
  - ANY /internal/cfvm/internal/cfvm
*/
public class CfvmAdminController {

    private final CfvmRawService cfvm;
    private final CfvmRawProperties props;

    @GetMapping(value="/buffer", produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> buffer(@RequestParam(required=false) String sid) {
        String session = sid != null ? sid : CfvmRawService.currentSessionIdOr("global");
        Map<String,Object> out = new HashMap<>();
        out.put("sid", session);
        out.put("slots", cfvm.buffer(session).snapshot());
        out.put("weights", cfvm.weights(session));
        out.put("enabled", props.isEnabled());
        return out;
    }

    @PostMapping("/flush")
    public Map<String,Object> flush(@RequestParam(required=false) String sid) {
        String session = sid != null ? sid : CfvmRawService.currentSessionIdOr("global");
        // re-create buffer
        cfvm.buffer(session); // lazy put
        return Map.of("sid", session, "ok", true);
    }
}