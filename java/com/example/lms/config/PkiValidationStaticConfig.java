package com.example.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;



/**
  * /.well-known/pki-validation/** 경로를 파일 시스템(upload-dir)에서 정적 서빙.
 * Sectigo/Comodo DCV 규정상 200 OK 직답이 필요하며 리다이렉트가 있으면 실패함.
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