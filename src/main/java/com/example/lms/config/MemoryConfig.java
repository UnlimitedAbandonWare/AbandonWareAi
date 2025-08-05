package com.example.lms.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
/**
 * 세션별 ChatMemory 창을 생성‑제공하는 전용 설정 클래스.
 * 별도 파일로 분리해 두면 LangChainConfig 의 복잡도가 줄어듭니다.
 */
@Configuration
public class MemoryConfig {

    @Bean("persistentChatMemoryProvider")  // ★ 이름 변경
    @Primary                               // 우선 선택
    public ChatMemoryProvider persistentChatMemoryProvider(
            @Value("${openai.api.history.max-messages:20}") int maxMsgs) {

        return sid -> MessageWindowChatMemory.builder()
                .id(sid)                   // ← 람다 파라미터로 수정
                .maxMessages(maxMsgs)      // LangChain4j 1.0.1 API
                .build();
    }
}
