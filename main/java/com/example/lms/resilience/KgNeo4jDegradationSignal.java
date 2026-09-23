package com.example.lms.resilience;

record KgNeo4jDegradationSignal(boolean degraded, String failureClass, String reason, double pressure) {
    static KgNeo4jDegradationSignal none() {
        return new KgNeo4jDegradationSignal(false, "none", "none", 0.0d);
    }
}
