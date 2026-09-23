package com.example.lms.api;

import com.example.lms.service.diagnostic.RuntimeDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagnostics endpoint that aggregates operational "recovery/block" states.
 *
 * This is meant for quick human/operator inspection; actuator endpoint
 * {@code /actuator/runtime} provides the same payload when exposed.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class RuntimeDiagnosticsController {

    private final RuntimeDiagnosticsService service;

    public RuntimeDiagnosticsController(RuntimeDiagnosticsService service) {
        this.service = service;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        return service.snapshot();
    }
}
