package com.example.lms.translation;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApiKeyManager {
    private final List<String> apiKeys;
    private int currentIndex = 0;
    private long currentUsage = 0;

    /** Logger for key rotation messages. */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiKeyManager.class);

    public ApiKeyManager(@Value("${google.translate.keys}") List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public synchronized String getKeyForText(String text) {
        int length = text.length();
        if (currentUsage + length > 500_000) {
            currentIndex = (currentIndex + 1) % apiKeys.size();
            currentUsage = 0;
            log.info("문자 수 한도 초과, API 키 전환: 인덱스={}", currentIndex);
        }
        currentUsage += length;
        return apiKeys.get(currentIndex);
    }
}
