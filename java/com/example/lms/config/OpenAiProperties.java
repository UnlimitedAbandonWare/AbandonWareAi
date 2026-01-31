// src/main/java/com/example/lms/config/OpenAiProperties.java
package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;



@Component
@ConfigurationProperties(prefix = "openai.chat.history")
public class OpenAiProperties {
    /** openai.chat.history.max-messages */
    private int maxMessages = 10;  // 기본값

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }
}