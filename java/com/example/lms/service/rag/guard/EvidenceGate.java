package com.example.lms.service.rag.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import com.example.lms.rag.model.QueryDomain;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;




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
    @Autowired
    private GuardProfileProps guardProfileProps;


    private static final Logger log = LoggerFactory.getLogger(EvidenceGate.class);


    private final double defaultThreshold;
    private final double followupThreshold;
    private final double memoryWeight;
    private final double kbWeight;

    public EvidenceGate(
            @Value("${verifier.coverage.threshold.default:0.05}") double defaultThreshold,
            @Value("${verifier.coverage.threshold.followup:0.02}") double followupThreshold,
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
    
    /**
     * Overload that accepts a QueryDomain and applies soft-pass semantics for game/subculture.
     */
    public boolean hasSufficientCoverage(
            String question,
            java.util.List<String> ragLines,
            java.util.List<String> memoryLines,
            java.util.List<String> kbLines,
            boolean isFollowUp,
            QueryDomain domain
    ) {
        boolean base = hasSufficientCoverage(question, ragLines, memoryLines, kbLines, isFollowUp);
        int evidenceCount = safeSize(ragLines) + safeSize(memoryLines) + safeSize(kbLines);

        // 게임/서브컬처: 증거 2개 이상이면 무조건 통과
        if (evidenceCount >= 2 &&
                (domain == QueryDomain.GAME || domain == QueryDomain.SUBCULTURE)) {
            return true;
        }

        // 학습/일반: 증거 3개 이상이면 base가 막아도 구제 (단, SENSITIVE는 제외)
        if (!base && evidenceCount >= 3 &&
                (domain == QueryDomain.STUDY || domain == QueryDomain.GENERAL)) {
            return true;
        }

        return base;
    }

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
    double ragCoverage = calculateCoverage(questionTokens, ragLines);
    double memoryCoverage = calculateCoverage(questionTokens, normalizeMemoryLines(memoryLines)) * memoryWeight;
    double kbCoverage = calculateCoverage(questionTokens, kbLines) * kbWeight;
    double combinedCoverage = 1.0 - (1.0 - ragCoverage) * (1.0 - memoryCoverage) * (1.0 - kbCoverage);
    int ragCount = safeSize(ragLines);
    int memCount = safeSize(memoryLines);
    int kbCount = safeSize(kbLines);
    int totalEvidence = ragCount + memCount + kbCount;
    GuardProfile profile = guardProfileProps.currentProfile();
    // 1) 증거가 아예 없으면 둘 다 실패
    if (totalEvidence == 0) {
        log.info("[EVIDENCE_GATE] No evidence at all → block (q={})", trimForLog(question));
        return false;
    }
    // 2) PROFILE_FREE(무메모리 Pro 잼미니) → 증거 1개만 있어도 무조건 통과
    if (profile == GuardProfile.PROFILE_FREE) {
        if (combinedCoverage == 0.0) {
            log.info("[EVIDENCE_GATE] FREE profile: coverage=0 but evidence exists → soft allow.");
        }
        return true;
    }
    // 3) PROFILE_MEMORY(기억저장 잼미니) → threshold 적용하되 soft-pass 관대하게
    double threshold = isFollowUp ? followupThreshold : defaultThreshold;
    boolean ok = combinedCoverage >= threshold;
    // Smart Pass: 점수 낮아도 증거 2개 이상이면 구제
    if (!ok && totalEvidence >= 2 && combinedCoverage > 0.01) {
        ok = true;
        log.info("[EVIDENCE_GATE] Smart Pass: score={} evidences={}", combinedCoverage, totalEvidence);
    }
    // Force Pass: 웹 증거 4개 이상이면 무조건 통과
    if (!ok && ragCount >= 4) {
        ok = true;
        log.info("[EVIDENCE_GATE] Force Pass: ragCount={}", ragCount);
    }
    // Ultra Pass: 증거 1개라도 + coverage > 0 이면 통과
    if (!ok && combinedCoverage > 0.0) {
        ok = true;
        log.info("[EVIDENCE_GATE] Ultra Pass: coverage={} evidences={}", combinedCoverage, totalEvidence);
    }
    return ok;
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
    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // Unicode NFC 정규화 (NFD/NFC 혼재 방지)
        return Normalizer.normalize(text, Normalizer.Form.NFC).trim();
    }


    /**
     * 문자열을 분석에 사용할 의미 있는 토큰 배열로 변환합니다. (알파벳, 한글, 숫자만 유지)
     */
    private String[] tokenize(String text) {
        String normalized = normalizeText(text);
        String cleanedText = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-z0-9]+", " ")
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
    private static boolean tokenMatch(String qToken, String docToken) {
        if (qToken == null || docToken == null) {
            return false;
        }

        // 정규화 적용
        qToken = normalizeText(qToken);
        docToken = normalizeText(docToken);

        if (qToken.isEmpty() || docToken.isEmpty()) {
            return false;
        }

        // 1. 완전 일치 (대소문자 무시)
        if (qToken.equalsIgnoreCase(docToken)) {
            return true;
        }

        // 2. 한글 토큰인 경우 특수 처리
        if (isKorean(qToken) && isKorean(docToken)) {
            String qStripped = stripKoreanParticles(qToken);
            String dStripped = stripKoreanParticles(docToken);

            // 2-1. 조사 제거 후 완전 일치
            if (!qStripped.isEmpty() && qStripped.equals(dStripped)) {
                return true;
            }

            // 2-2. 조사 제거 후 포함 관계 (2글자 이상으로 완화)
            int minLen = Math.min(qStripped.length(), dStripped.length());
            if (minLen >= 2) {
                if (qStripped.contains(dStripped) || dStripped.contains(qStripped)) {
                    return true;
                }
            }

            return false;
        }


        // 3. 비한글 (영어/숫자) - 기존 로직 유지
        if (qToken.length() >= 2 && docToken.length() >= 2) {
            return qToken.contains(docToken) || docToken.contains(qToken);
        }

        return false;
    }

private static boolean isKorean(String s) {
    if (s == null || s.isEmpty()) {
        return false;
    }
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
            return true;
        }
    }
    return false;
}

// 1글자 조사 목록 (보수적 접근)
private static final String[] KOREAN_PARTICLES_1 = {
        "은", "는", "이", "가", "을", "를", "에", "의", "도", "만", "와", "과", "로"
};

// 2글자 조사 목록
private static final String[] KOREAN_PARTICLES_2 = {
        "에서", "에게", "으로", "부터", "까지", "라고", "처럼"
};

/**
 * 한글 토큰에서 조사를 제거하여 어근 반환 (2글자 이하는 보존)
 */
private static String stripKoreanParticles(String token) {
    if (token == null || token.length() <= 2) {
        return token; // 너무 짧은 토큰은 노이즈 방지를 위해 그대로
    }

    // 2글자 조사 우선 제거
    for (String p : KOREAN_PARTICLES_2) {
        if (token.endsWith(p) && token.length() > p.length()) {
            return token.substring(0, token.length() - p.length());
        }
    }

    // 1글자 조사 제거
    for (String p : KOREAN_PARTICLES_1) {
        if (token.endsWith(p) && token.length() > 1) {
            return token.substring(0, token.length() - 1);
        }
    }

    return token;
}

private double calculateCoverage(String[] questionTokens, List<String> evidenceLines) {
    if (evidenceLines == null || evidenceLines.isEmpty() || questionTokens.length == 0) {
        return 0.0;
    }

    int hits = 0;

    for (String qToken : questionTokens) {
        if (qToken == null || qToken.length() < 2) {
            continue;
        }
        boolean matched = false;
        for (String line : evidenceLines) {
            if (line == null || line.isEmpty()) {
                continue;
            }

            String lowerLine = line.toLowerCase(Locale.ROOT);
            if (lowerLine.contains(qToken)) {
                matched = true;
                break;
            }

            String[] docTokens = tokenize(line);
            for (String docToken : docTokens) {
                if (tokenMatch(qToken, docToken)) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                break;
            }
        }
        if (matched) {
            hits++;
        }
    }

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

    private static int safeSize(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }


    /**
     * 로그 출력을 위해 문자열을 적절한 길이(예: 50자)로 자릅니다.
     */
    private String trimForLog(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= 50) {
            return text;
        }
        return text.substring(0, 50) + "...";
    }

}