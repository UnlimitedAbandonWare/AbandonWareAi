// src/main/java/com/example/lms/prompt/SystemPrompt.java
package com.example.lms.prompt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;



/**
 * 시스템 프롬프트를 관리하고 포맷팅하는 컴포넌트.
 * application.properties 파일에서 프롬프트 내용을 주입받아 사용합니다.
 */
@Component
public class SystemPrompt {

    private final String template;

    /**
     * 생성자 주입을 통해 application.properties의 'gpt.system.prompt' 값을 가져옵니다.
     * @param template gpt.system.prompt 값
     */
    public SystemPrompt(@Value("${gpt.system.prompt}") String template) {
        this.template = template;
    }

    /**
     * 시스템 프롬프트와 사용자 메시지를 결합하여 LLM에 전달할 최종 프롬프트를 생성합니다.
     * @param userMessage 사용자의 입력 메시지 (영문으로 번역된 상태)
     * @return 최종 프롬프트 문자열
     */
    public String format(String userMessage) {
        // 필요에 따라 String.format, MessageFormat 등을 사용하여 더 복잡한 템플릿 구성 가능
        return template + "\n\nUser: " + userMessage;
    }
}