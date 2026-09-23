package com.example.lms.api.actuator;

import com.example.lms.learning.ops.RagLearningOpsDashboardService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Endpoint(id = "ragLearningOps")
public class RagLearningOpsEndpoint {

    private final RagLearningOpsDashboardService service;

    public RagLearningOpsEndpoint(RagLearningOpsDashboardService service) {
        this.service = service;
    }

    @ReadOperation
    public Map<String, Object> metrics() {
        return service.metrics();
    }
}
