package com.example.lms.service.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gpt-search.brave")
public record BraveSearchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://api.search.brave.com/res/v1/web/search") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("1.0") double qpsLimit,
        @DefaultValue("2000") int monthlyQuota,
        @DefaultValue("500") long acquireTimeoutMs
) {
}
