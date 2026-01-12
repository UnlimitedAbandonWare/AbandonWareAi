package com.abandonware.ai.api;

import com.abandonware.ai.service.TranslationTrainingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/translate")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.api.TranslateController
 * Role: controller
 * Key Endpoints: GET /api/translate/rules, POST /api/translate/train, ANY /api/translate/api/translate
 * Dependencies: com.abandonware.ai.service.TranslationTrainingService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.api.TranslateController
role: controller
api:
  - GET /api/translate/rules
  - POST /api/translate/train
  - ANY /api/translate/api/translate
*/
public class TranslateController {
    private final TranslationTrainingService svc;
    public TranslateController(TranslationTrainingService svc) { this.svc = svc; }

    @PostMapping("/train")
    public String train(@RequestBody String samples) {
        svc.train(samples);
        return "ok";
    }

    @GetMapping("/rules")
    public String rules() { return "[]"; }
}