package com.example.lms.api.internal;

import com.example.lms.service.soak.SoakRunResult;
import com.example.lms.service.soak.SoakTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API controller exposing a soak test endpoint.  The endpoint
 * accepts a parameter {@code k} specifying the maximum number of queries
 * to sample and an optional {@code topic} filter (genshin, default or
 * all).  Results include both per-item details and aggregated metrics.
 */
@RestController
@RequestMapping("/internal/soak")
@RequiredArgsConstructor
public class SoakApiController {
    private final SoakTestService soakTestService;

    @GetMapping("/run")
    public SoakRunResult run(
            @RequestParam(name = "k", defaultValue = "10") int k,
            @RequestParam(name = "topic", defaultValue = "all") String topic) {
        return soakTestService.runSoak(k, topic);
    }
}