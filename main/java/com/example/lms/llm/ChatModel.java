// src/main/java/com/example/lms/llm/ChatModel.java
package com.example.lms.llm;


public interface ChatModel {
    /** 단일 프롬프트 → 단일 텍스트 */
    String generate(String llmGenerationPrompt);

    /** 파라미터 버전(옵션) */
    default String generate(String llmGenerationPrompt, double temperature, int maxTokens) {
        return generate(llmGenerationPrompt);
    }
}
