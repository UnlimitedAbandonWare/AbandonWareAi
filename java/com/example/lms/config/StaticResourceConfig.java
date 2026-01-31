package com.example.lms.config;

import com.example.lms.plugin.image.storage.ImageStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;



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

    private final ImageStorageProperties props;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String root = props.root();
        if (root == null || root.isBlank()) {
            return;
        }
        String prefix = props.publicPrefix() == null ? "/generated-images/" : props.publicPrefix();
        // Normalise file system path for Windows (replace backslashes)
        String location = "file:" + root.replace("\\", "/") + "/";
        registry.addResourceHandler(prefix + "**")
                .addResourceLocations(location);
    }
}