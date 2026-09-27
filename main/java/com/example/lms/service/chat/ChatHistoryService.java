// src/main/java/com/example/lms/service/chat/ChatHistoryService.java
package com.example.lms.service.chat;


public interface ChatHistoryService {
    String summarizeRecentLowConfidence(int limit);
}