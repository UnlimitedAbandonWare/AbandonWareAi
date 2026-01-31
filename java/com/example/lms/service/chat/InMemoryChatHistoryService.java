// src/main/java/com/example/lms/service/chat/InMemoryChatHistoryService.java
package com.example.lms.service.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;



@Service
@ConditionalOnMissingBean(ChatHistoryService.class) // 실구현체가 없을 때만 등록
public class InMemoryChatHistoryService implements ChatHistoryService {
    @Override
    public String summarizeRecentLowConfidence(int limit) {
        return ""; // 아직 요약 로직이 없으면 빈 문자열 반환
    }
}