package com.example.lms.mcp;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/mcp")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.mcp.McpSessionRouter
 * Role: controller
 * Key Endpoints: POST /mcp/hello, ANY /mcp/mcp
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.mcp.McpSessionRouter
role: controller
api:
  - POST /mcp/hello
  - ANY /mcp/mcp
*/
public class McpSessionRouter {

    public static record Capabilities(boolean brave, boolean zeroBreak) {}
    public static record PolicyContract(boolean brave, int citationMin, int hostDiversityMin) {}

    @PostMapping(value = "/hello", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PolicyContract> hello(@RequestBody Capabilities caps) {
        boolean brave = caps != null && caps.brave();
        int cit = brave ? 3 : 2;
        int host = brave ? 2 : 1;
        return Mono.just(new PolicyContract(brave, cit, host));
    }
}