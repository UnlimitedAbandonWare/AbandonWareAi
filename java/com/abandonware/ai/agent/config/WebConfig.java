package com.abandonware.ai.agent.config;

import com.abandonware.ai.agent.consent.ConsentInterceptor;
import com.abandonware.ai.agent.context.ContextBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.abandonware.ai.agent.identity.IdentityInterceptor;



@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public IdentityInterceptor identityInterceptor(ContextBridge bridge) {
        return new IdentityInterceptor(bridge);
    }


    @Bean
    public ContextBridge contextBridge() {
        return new ContextBridge();
    }

    @Bean
    public ConsentInterceptor consentInterceptor(ContextBridge bridge) {
        return new ConsentInterceptor(bridge);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor(contextBridge()));
        registry.addInterceptor(consentInterceptor(contextBridge()));
    }
}