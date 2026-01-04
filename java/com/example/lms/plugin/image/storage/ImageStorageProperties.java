package com.example.lms.plugin.image.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;



/**
 * Configuration properties governing where generated images are stored and
 * how they are served to clients.  The {@code root} location points to
 * a directory on the local filesystem where image files will be persisted.
 * The {@code publicPrefix} defines the URL path under which these
 * files will be exposed via Spring MVC.  See {@link com.example.lms.config.StaticResourceConfig}
 * for the corresponding resource handler configuration.
 */
@ConfigurationProperties(prefix = "image.storage")
public record ImageStorageProperties(String root, String publicPrefix) {
}