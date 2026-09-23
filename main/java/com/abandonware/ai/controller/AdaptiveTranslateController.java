package com.abandonware.ai.controller;

import com.abandonware.ai.service.AdaptiveTranslationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AdaptiveTranslateController {
    private final AdaptiveTranslationService svc;
    public AdaptiveTranslateController(AdaptiveTranslationService svc) { this.svc = svc; }

    @PostMapping("/adaptive-translate")
    public String translate(@RequestParam String text, @RequestParam(defaultValue = "ko") String lang) {
        return svc.translate(text, lang);
    }
}