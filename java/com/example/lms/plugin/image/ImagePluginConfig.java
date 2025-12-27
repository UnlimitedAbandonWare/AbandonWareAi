package com.example.lms.plugin.image;

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
        com.example.lms.plugin.image.storage.ImageStorageProperties.class,
        com.example.lms.plugin.image.jobs.ImageJobProperties.class
})
public class ImagePluginConfig {
}