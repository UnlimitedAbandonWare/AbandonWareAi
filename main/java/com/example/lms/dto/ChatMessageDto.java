// src/main/java/com/example/lms/dto/ChatMessageDto.java
package com.example.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



/**
 * 채팅창 단일 메시지 DTO (프런트 JSON 1:1 매핑)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {

    /** 대화 턴(줄) 번호  */
    private long turn;

    /** "user" | "assistant" | (필요 시 "system") */
    private String role;

    /** 실제 메시지 텍스트 */
    private String content;
}