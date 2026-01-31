
// src/main/java/com/example/lms/service/NotificationService.java
package com.example.lms.service;

import org.springframework.stereotype.Service;



@Service
public class NotificationService {
    public void sendPush(Long userId, String message) {
        // Implementation shim: publish push messages via Redis or RabbitMQ.
    }

    public void sendKakao(String phone, String message) {
        // Implementation shim: integrate with the KakaoTalk API.
    }
}