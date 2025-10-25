// src/main/java/com/example/lms/service/chat/NoopChatHistoryService.java
package com.example.lms.service.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;



/** 실제 구현이 없을 때 빈으로 대체되는 Noop */
@Service
@ConditionalOnMissingBean(ChatHistoryService.class)
public class NoopChatHistoryService implements ChatHistoryService {
    @Override
    public String summarizeRecentLowConfidence(int limit) {
        return "";
    }
}