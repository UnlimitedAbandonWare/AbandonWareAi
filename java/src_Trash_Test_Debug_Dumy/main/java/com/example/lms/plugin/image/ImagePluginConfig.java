package com.example.lms.plugin.image;

import com.example.lms.plugin.jobs.ImageJobProperties;
import com.example.lms.plugin.storage.ImageStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that explicitly enables binding of the
 * {@link OpenAiImageProperties} record.  Without this class Spring
 * Boot would ignore the {@code openai.image.*} namespace because
 * {@link org.springframework.boot.context.properties.ConfigurationProperties}
 * scanning is disabled by default in this module.  Declaring this
 * configuration ensures that the properties are bound and made available
 * for injection into the {@link OpenAiImageService}.
 */
@Configuration
@EnableConfigurationProperties({
        OpenAiImageProperties.class,
        ImageStorageProperties.class,
        ImageJobProperties.class,
        // Register Gemini image properties so that Spring binds the gemini.image.* namespace.
        GeminiImageProperties.class
})
public class ImagePluginConfig {
}