
package com.example.lms.service.llm;


public interface LlmClient {
    /** 프롬프트를 주면 LLM의 순수 텍스트 응답을 돌려준다. (예: JSON 문자열) */
    String complete(String prompt);

    /**
     * 동일한 LLM 호출이라도 단계별(NightmareBreaker key별)로 상태를 공유해야
     * 오케스트레이션이 "접목"된다. 기본 구현은 하위 호환을 위해 complete()로 위임한다.
     */
    default String completeWithKey(String breakerKey, String prompt) {
        return complete(prompt);
    }
}