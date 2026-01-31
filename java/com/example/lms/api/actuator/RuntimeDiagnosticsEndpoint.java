package com.example.lms.api.actuator;

import com.example.lms.service.diagnostic.RuntimeDiagnosticsService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Endpoint(id = "runtime")
public class RuntimeDiagnosticsEndpoint {

    private final RuntimeDiagnosticsService service;

    public RuntimeDiagnosticsEndpoint(RuntimeDiagnosticsService service) {
        this.service = service;
    }

    @ReadOperation
    public Map<String, Object> runtime() {
        return service.snapshot();
    }
}
