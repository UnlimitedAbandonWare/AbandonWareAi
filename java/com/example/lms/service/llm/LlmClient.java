
package com.example.lms.service.llm;


public interface LlmClient {
    /** 프롬프트를 주면 LLM의 순수 텍스트 응답을 돌려준다. (예: JSON 문자열) */
    String complete(String prompt);
}