package com.abandonware.ai.api.internal;

import com.abandonware.ai.service.soak.SoakTestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/soak")
public class SoakApiController {
    private final SoakTestService service;
    public SoakApiController(SoakTestService service) { this.service = service; }

    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "10") int k,
                                   @RequestParam(defaultValue = "default") String topic) {
        return service.run(k, topic);
    }
}