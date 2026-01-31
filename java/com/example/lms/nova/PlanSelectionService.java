package com.example.lms.nova;

import org.springframework.stereotype.Service;



@Service
public class PlanSelectionService {
    private final PlanDslLoader loader = new PlanDslLoader();

    public BravePlan resolve() {
        if (NovaRequestContext.isBrave()) {
            return loader.load("brave.v1");
        }
        return loader.load("safe_autorun.v1");
    }
}