package com.example.lms.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;

@Slf4j
@Primary
@Component  // 스프링이 자동으로 빈 등록
public class LoggingSseEventPublisher implements SseEventPublisher {

    @Override
    public void emit(String type, Object payload) {
        // 실제 SSE 송신 대신 로그로 대체 (No-Op 성격)
        if (log.isDebugEnabled()) {
            log.debug("SSE[{}] -> {}", type, payload);
        }
    }
}
