package com.example.lms.api;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "message.gateway")
public class MessageGatewayProperties {
    private String apiBaseUrl = "https://example.invalid";
    private int webclientConnectTimeoutMs = 5000;
    private int webclientReadTimeoutMs = 10000;
}
