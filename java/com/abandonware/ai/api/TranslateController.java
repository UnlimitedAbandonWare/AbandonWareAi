package com.abandonware.ai.api;

import com.abandonware.ai.service.TranslationTrainingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/translate")
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