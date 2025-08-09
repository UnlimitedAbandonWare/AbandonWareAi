// 경로: src/main/java/com/example/lms/dto/ChatRequestDto.java
package com.example.lms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * [최종 통합본] 시스템 전역에서 사용되는 채팅 요청 DTO.
 * 여러 컨트롤러와 서비스의 요구사항을 모두 통합하여,
 * Builder 패턴, 모든 필드, 모든 생성자를 지원합니다.
 */
@Data // @Getter, @Setter, @ToString, @EqualsAndHashCode, @RequiredArgsConstructor 통합
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDto {

        /* ────── 필수 프롬프트 ────── */
        private String message;

        /* ────── 옵션 프롬프트 ────── */
        private String systemPrompt;
        private List<Message> history;

        /* ────── 모델 및 샘플링 파라미터 ────── */
        private String model;

        @Builder.Default
        private Double temperature = 0.7;

        @Builder.Default
        @JsonProperty("top_p")
        private Double topP = 1.0;

        @Builder.Default
        private Double frequencyPenalty = 0.0;

        @Builder.Default
        private Double presencePenalty = 0.0;

        @Builder.Default
        private Integer maxTokens = 1024;

        /* ────── 서비스 기능 플래그 (API Controller에서 사용) ────── */
        private Long sessionId;

        @Builder.Default
        private boolean useAdaptive = false;

        @Builder.Default
        private boolean autoTranslate = false;

        @Builder.Default
        private boolean useWebSearch = false;

        /* ────── 내부 Message DTO ────── */
        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Message {
                private String role;
                private String content;
        }
}