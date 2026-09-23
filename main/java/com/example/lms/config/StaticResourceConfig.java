package com.example.lms.config;

import com.example.lms.plugin.image.storage.ImageStorageProperties;
import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Configure static resource handling to serve generated images from the
 * local file system.  When {@code image.storage.root} is configured,
 * this configuration registers a resource handler that exposes files
 * under the storage root at the URL path defined by
 * {@code image.storage.public-prefix}.  Without this configuration the
 * generated image files would not be reachable via HTTP.
 */
@Configuration
@RequiredArgsConstructor
public class StaticResourceConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceConfig.class);

    private final ImageStorageProperties props;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path root = FileSystemImageStorage.resolveRoot(props);
            String prefix = FileSystemImageStorage.publicPrefix(props);
            String location = root.toUri().toString();
            registry.addResourceHandler(prefix + "**")
                    .addResourceLocations(location);
        } catch (InvalidPathException ex) {
            log.warn("[AWX][verify] image static resource handler disabled disabledReason=invalid_storage_root exceptionType={}",
                    ex.getClass().getSimpleName());
        }
    }
}
