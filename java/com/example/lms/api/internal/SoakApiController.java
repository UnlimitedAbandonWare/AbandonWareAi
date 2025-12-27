package com.example.lms.api.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.lms.service.soak.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/soak")
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakApiController {
    private final SoakTestService service;
    public SoakApiController(SoakTestService service) { this.service = service; }

    @GetMapping("/run")
    public ResponseEntity<SoakReport> run(@RequestParam(defaultValue = "10") int k,
                                          @RequestParam(defaultValue = "all") String topic) {
        return ResponseEntity.ok(service.run(k, topic));
    }

    /**
     * Quick soak endpoint with a fixed schema JSON response.
     *
     * Example:
     * /internal/soak/quick?topic=naver-bing-fixed10&k=10
     */
    @GetMapping("/quick")
    public ResponseEntity<SoakQuickReport> quick(@RequestParam(defaultValue = "10") int k,
                                                 @RequestParam(defaultValue = "all") String topic) {
        return ResponseEntity.ok(service.runQuick(k, topic));
    }
}