package com.example.lms.service.rag.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;




/**
 * 질문에 답변하기에 앞서 수집된 컨텍스트(증거)가 충분한지 양적으로 판단하는 게이트입니다.
 * <p>
 * 질문의 핵심 토큰들이 RAG, 메모리, KB 등 여러 출처에 얼마나 분포해 있는지 커버리지 점수를 계산합니다.
 * 이 클래스는 메모리 정규화 기능을 내장하여 외부 의존성 없이 단독으로 동작합니다.
 * 점수가 설정된 임계값 미만일 경우, 정보 부족으로 인한 환각을 방지하기 위해 답변 생성을 중단시킬 수 있습니다.
 * </p>
 */
@Component
public class EvidenceGate {

    private final double defaultThreshold;
    private final double followupThreshold;
    private final double memoryWeight;
    private final double kbWeight;

    public EvidenceGate(
            @Value("${verifier.coverage.threshold.default:0.30}") double defaultThreshold,
            @Value("${verifier.coverage.threshold.followup:0.25}") double followupThreshold,
            @Value("${verifier.coverage.weight.memory:0.6}") double memoryWeight,
            @Value("${verifier.coverage.weight.kb:0.8}") double kbWeight) {
        this.defaultThreshold = defaultThreshold;
        this.followupThreshold = followupThreshold;
        this.memoryWeight = memoryWeight;
        this.kbWeight = kbWeight;
    }

    /**
     * 수집된 증거들의 커버리지 점수를 계산하여 임계값을 넘는지 확인합니다. (핵심 구현)
     *
     * @param question      사용자 원본 질문
     * @param ragLines      RAG 검색 결과 스니펫 목록
     * @param memoryLines   정규화가 필요한 원본 대화 메모리 라인 목록
     * @param kbLines       내부 KB(KnowledgeBase) 조회 결과 목록
     * @param isFollowUp    후속 질문 여부 (임계값 완화용)
     * @return 증거가 충분하면 {@code true}, 아니면 {@code false}
     */
    public boolean hasSufficientCoverage(String question,
                                         List<String> ragLines,
                                         List<String> memoryLines,
                                         List<String> kbLines,
                                         boolean isFollowUp) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String[] questionTokens = tokenize(question);
        if (questionTokens.length == 0) {
            return false;
        }

        // 각 출처별 커버리지 점수 계산 (메모리는 내부 정규화 후 가중치 적용)
        double ragCoverage = calculateCoverage(questionTokens, ragLines);
        double memoryCoverage = calculateCoverage(questionTokens, normalizeMemoryLines(memoryLines)) * memoryWeight;
        double kbCoverage = calculateCoverage(questionTokens, kbLines) * kbWeight;

        // 독립적인 확률을 결합하는 방식으로 전체 커버리지 계산
        double combinedCoverage = 1.0 - (1.0 - ragCoverage) * (1.0 - memoryCoverage) * (1.0 - kbCoverage);

        double threshold = isFollowUp ? followupThreshold : defaultThreshold;
        return combinedCoverage >= threshold;
    }

    /**
     * (호환용 오버로드) 레거시 시그니처를 지원하기 위한 브릿지 메서드입니다.
     * LangChainRAGService 등에서 호출합니다.
     */
    public boolean hasSufficientCoverage(String question,
                                         List<String> ragLines,
                                         String externalContext,
                                         int minEv) {
        // externalContext를 KB 라인처럼 취급하여 핵심 로직에 위임합니다.
        return hasSufficientCoverage(
                question,
                ragLines,
                List.of(),                // memoryLines 없음
                toLines(externalContext), // KB 라인으로 사용
                false                     // isFollowUp 정보가 없으므로 false
        );
    }


    // --- Private Helper Methods ---

    /**
     * 문자열을 분석에 사용할 의미 있는 토큰 배열로 변환합니다. (알파벳, 한글, 숫자만 유지)
     */
    private String[] tokenize(String text) {
        String cleanedText = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}a-z0-9]+", " ")
                .trim();
        if (cleanedText.isEmpty()) {
            return new String[0];
        }
        return Arrays.stream(cleanedText.split("\\s+"))
                .filter(word -> word.length() >= 2)
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * 질문 토큰이 증거 라인들에서 얼마나 커버되는지 비율(0.0 ~ 1.0)을 계산합니다.
     */
    private double calculateCoverage(String[] questionTokens, List<String> evidenceLines) {
        if (evidenceLines == null || evidenceLines.isEmpty() || questionTokens.length == 0) {
            return 0.0;
        }

        long hits = Arrays.stream(questionTokens)
                .filter(token -> evidenceLines.stream()
                        .anyMatch(line -> line != null && line.toLowerCase(Locale.ROOT).contains(token)))
                .count();

        return (double) hits / questionTokens.length;
    }

    /**
     * (내장된 기능) 원본 메모리 라인 목록을 받아 정규화된 증거 목록으로 변환합니다.
     * 빈 줄, 주석, 중복을 제거합니다.
     */
    private List<String> normalizeMemoryLines(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return List.of();
        }

        return rawLines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.length() > 1)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * (호환용 헬퍼) 단일 문자열을 줄바꿈 기준으로 분리하여 라인 목록으로 만듭니다.
     */
    private static List<String> toLines(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split("\\R+"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }
}