package com.example.lms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;



/**
 * Serves static files for ACME HTTP-01 challenge.
 * Maps: /.well-known/acme-challenge/**
 */
@Configuration
public class AcmeChallengeStaticConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/.well-known/acme-challenge/**")
                .addResourceLocations("file:./.well-known/acme-challenge/");
    }
}