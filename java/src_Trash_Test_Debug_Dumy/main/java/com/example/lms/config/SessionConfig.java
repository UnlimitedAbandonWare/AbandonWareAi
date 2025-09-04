
// src/main/java/com/example/lms/config/SessionConfig.java
        package com.example.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpSession;

import java.util.function.Supplier;
import com.example.lms.common.ChatSessionScope;

@Configuration
public class SessionConfig {
    @Bean
    /** ★ 현재 chat_session.id( Long )를 ThreadLocal 에 보관 */
    public Supplier<Long> sessionIdProvider() {
        // 빈 상태(null)면 0L 반환해 NPE 예방
        return ChatSessionScope::current;
    };
}



