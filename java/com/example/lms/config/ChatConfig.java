// src/main/java/com/example/lms/config/ChatConfig.java
package com.example.lms.config;

import com.example.lms.service.chat.ChatHistoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class ChatConfig {
    @Bean
    @ConditionalOnMissingBean(ChatHistoryService.class)
    public ChatHistoryService chatHistoryService() {
        return limit -> ""; // no-op
    }
}