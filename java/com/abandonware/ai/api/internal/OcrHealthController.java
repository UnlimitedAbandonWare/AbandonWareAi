package com.abandonware.ai.api.internal;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/health")
public class OcrHealthController {

    private final com.abandonware.ai.health.OcrHealthIndicator indicator;

    public OcrHealthController(com.abandonware.ai.health.OcrHealthIndicator indicator) {
        this.indicator = indicator;
    }

    @GetMapping("/ocr")
    public ResponseEntity<?> ocr() {
        Health h = indicator.health();
        return ResponseEntity.status(h.getStatus().equals(Status.UP) ? 200 : 503).body(h);
    }
}