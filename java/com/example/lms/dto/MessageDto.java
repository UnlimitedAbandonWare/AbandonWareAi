package com.example.lms.dto;

import lombok.Data;



@Data
public class MessageDto {
        private String role;       // "system" | "user" | "assistant"
        private String content;    // HTML 포함 가능
        private String modelUsed;  // (선택) assistant인 경우 표시
}