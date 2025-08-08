
// src/main/java/com/example/lms/service/NotificationService.java
package com.example.lms.service;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    public void sendPush(Long userId, String message) {
        // TODO: Redis/RabbitMQ publish push message
    }

    public void sendKakao(String phone, String message) {
        // TODO: 카카오톡 API 연동
    }
}