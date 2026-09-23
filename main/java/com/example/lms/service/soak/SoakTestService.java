package com.example.lms.service.soak;

public interface SoakTestService {
    SoakReport run(int k, String topic);

    /**
     * Quick soak run with a fixed JSON schema.
     *
     * <p>Designed for automated smoke checks and dashboards.</p>
     */
    SoakQuickReport runQuick(int k, String topic);
}