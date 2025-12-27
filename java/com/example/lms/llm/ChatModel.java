// src/main/java/com/example/lms/llm/ChatModel.java
package com.example.lms.llm;


public interface ChatModel {
    /** 단일 프롬프트 → 단일 텍스트 */
    String generate(String prompt);

    /** 파라미터 버전(옵션) */
    default String generate(String prompt, double temperature, int maxTokens) {
        return generate(prompt);
    }
}