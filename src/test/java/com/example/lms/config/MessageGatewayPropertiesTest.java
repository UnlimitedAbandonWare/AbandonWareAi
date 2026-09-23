package com.example.lms.config;

import com.example.lms.api.MessageGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class MessageGatewayPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessageGatewayProperties.class)
    static class Bindings {}

    @Test
    void genericGatewayBindsItsOwnSettings() {
        new ApplicationContextRunner().withUserConfiguration(Bindings.class)
                .withPropertyValues(
                        "message.gateway.api-base-url=https://gateway.example.invalid",
                        "message.gateway.webclient-connect-timeout-ms=1234",
                        "message.gateway.webclient-read-timeout-ms=2345")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    MessageGatewayProperties gateway = context.getBean(MessageGatewayProperties.class);
                    assertEquals("https://gateway.example.invalid", gateway.getApiBaseUrl());
                    assertEquals(1234, gateway.getWebclientConnectTimeoutMs());
                    assertEquals(2345, gateway.getWebclientReadTimeoutMs());
                });
    }
}
