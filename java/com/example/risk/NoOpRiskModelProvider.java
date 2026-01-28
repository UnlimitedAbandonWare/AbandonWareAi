package com.example.risk;

import org.springframework.stereotype.Component;



/**
 * Default no-op provider until a trained classifier is wired.
 */
@Component
public class NoOpRiskModelProvider implements RiskModelProvider {
    @Override
    public Classifier get() {
        return null;
    }
}