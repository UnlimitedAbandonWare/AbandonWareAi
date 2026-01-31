// src/main/java/com/example/lms/service/translator/BasicTranslator.java
package com.example.lms.service.translator;


/**
 * 가장 단순한 “문장 단위 번역기” 역할.
 * - 실제 서비스에선 OpenAI·Papago·DeepL 등으로 교체하면 됩니다.
 */
public interface BasicTranslator {

    /**
     * @param text     원문
     * @param srcLang  원문 언어 코드 (예: "ko")
     * @param tgtLang  목표 언어 코드 (예: "en")
     * @return 번역된 문자열
     */
    String translate(String text, String srcLang, String tgtLang);
}