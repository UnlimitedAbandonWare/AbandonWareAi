package com.example.lms.config;

import com.example.lms.web.RuleBreakInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.config.WebMvcConfig
 * Role: config
 * Dependencies: com.example.lms.web.RuleBreakInterceptor
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.config.WebMvcConfig
role: config
*/
public class WebMvcConfig implements WebMvcConfigurer {
    private final RuleBreakInterceptor rb;
    public WebMvcConfig(RuleBreakInterceptor rb) { this.rb = rb; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rb);
    }
}