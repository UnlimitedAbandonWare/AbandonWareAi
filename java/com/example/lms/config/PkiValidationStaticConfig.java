package com.example.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves static files for PKI validation from the configured upload directory.
 * Maps: /.well-known/pki-validation/**
 */
@Configuration
public class PkiValidationStaticConfig implements WebMvcConfigurer {

    @Value("${pki.validation.upload-dir:./.well-known/pki-validation}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/.well-known/pki-validation/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}
