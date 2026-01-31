package com.example.lms.probe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;




/**
 * Exploratory soak runner: replace with your own engine wiring.
 * Note: @Disabled by default to avoid hitting external services in CI.
 */
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SearchSoakRunner {

    static final List<String> QUERIES = Arrays.asList(
        "원신 스커크 최신 패치",
        "LangChain4j 1.0.1 PromptBuilder",
        "서울 재개발 구역 통계 2024",
        "쿠버네티스 HPA vs VPA 차이",
        "대한민국 금리 인상 일정",
        "네이버 검색 연산자 정리",
        "빙 웹 검색 고급 문법",
        "React 19 Server Actions 예제",
        "한국 기초과학원 연차보고서 2023",
        "다중모달 RAG 설계 패턴"
    );

    @Test @Disabled("wire this test to your retriever/service before running locally")
    void run() {
        for (String q : QUERIES) {
            System.out.println("{"query":"" + q + "","hit":null,"evidence":null,"latencyMs":null}");
        }
    }
}