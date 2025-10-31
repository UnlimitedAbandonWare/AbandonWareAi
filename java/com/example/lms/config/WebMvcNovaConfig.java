package com.example.lms.config;

import com.example.lms.nova.RuleBreakInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;



@Configuration
public class WebMvcNovaConfig implements WebMvcConfigurer {
    private final RuleBreakInterceptor interceptor;
    @Autowired
    public WebMvcNovaConfig(RuleBreakInterceptor interceptor) { this.interceptor = interceptor; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**");
    }
}