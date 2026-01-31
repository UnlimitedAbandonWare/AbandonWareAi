package com.example.lms.plugin.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;



/**
 * Configuration properties for the OpenAI image generation plugin.  These
 * settings are deliberately isolated from existing OpenAI configuration
 * classes to avoid accidental reuse of unrelated properties.  The
 * {@code endpoint} must be specified explicitly; the {@code apiKey} is
 * optional and may be supplied via an environment variable.
 */
@Validated
@ConfigurationProperties(prefix = "openai.image")
public record OpenAiImageProperties(
        boolean enabled,
        @Nullable String endpoint,
        @Nullable String apiKey
) {
}