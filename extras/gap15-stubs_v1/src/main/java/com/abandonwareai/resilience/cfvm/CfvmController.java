package com.abandonwareai.resilience.cfvm;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;

@RestController
@RequestMapping("/internal")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.resilience.cfvm.CfvmController
 * Role: controller
 * Key Endpoints: GET /internal/cfvm/ping, ANY /internal/internal
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.resilience.cfvm.CfvmController
role: controller
api:
  - GET /internal/cfvm/ping
  - ANY /internal/internal
*/
public class CfvmController {
    @GetMapping("/cfvm/ping") public ResponseEntity<String> ping(){ return ResponseEntity.ok("cfvm-ok"); }

}