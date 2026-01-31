package com.abandonware.ai.controller;

import com.abandonware.ai.service.AdaptiveTranslationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.controller.AdaptiveTranslateController
 * Role: controller
 * Key Endpoints: POST /api/adaptive-translate, ANY /api/api
 * Dependencies: com.abandonware.ai.service.AdaptiveTranslationService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.controller.AdaptiveTranslateController
role: controller
api:
  - POST /api/adaptive-translate
  - ANY /api/api
*/
public class AdaptiveTranslateController {
    private final AdaptiveTranslationService svc;
    public AdaptiveTranslateController(AdaptiveTranslationService svc) { this.svc = svc; }

    @PostMapping("/adaptive-translate")
    public String translate(@RequestParam String text, @RequestParam(defaultValue = "ko") String lang) {
        return svc.translate(text, lang);
    }
}