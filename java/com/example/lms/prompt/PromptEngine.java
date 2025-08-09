package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import java.util.List;

/**
 * 사용자 질문과 검색된 문서를 기반으로 LLM에 전달할 최종 프롬프트를 생성하는 역할을 정의하는 인터페이스.
 */
public interface PromptEngine {

    /**
     * 최종 프롬프트 문자열을 생성합니다.
     *
     * @param question 사용자 원본 질문
     * @param docs     관련성 순으로 정렬된 문서(Content) 목록
     * @return LLM에 전달될 완성된 프롬프트 문자열
     */
    String createPrompt(String question, List<Content> docs);
}

