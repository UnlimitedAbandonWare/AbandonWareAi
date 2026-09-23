package com.example.lms.config;

import ai.abandonware.nova.orch.trace.MlaOtelBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MlaOtelBridgeConfig {

    public MlaOtelBridgeConfig(@Value("${otel.mla.bridge.enabled:false}") boolean enabled) {
        MlaOtelBridge.configure(enabled);
    }
}
