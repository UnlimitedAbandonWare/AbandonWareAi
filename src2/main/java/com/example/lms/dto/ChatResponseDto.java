// src/main/java/com/example/lms/dto/ChatResponseDto.java
package com.example.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter @AllArgsConstructor
public class ChatResponseDto {
    private final String content;        // AI가 생성한 최종 답변
    // 필요하다면 번역본, 토큰 사용량 등을 필드로 확장
}
