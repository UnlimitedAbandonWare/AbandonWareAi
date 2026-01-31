package com.example.lms.service.verification;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * [시선1 핵심] 시간 정합성 검증기.
 *
 * <p>
 * 예시:
 * LLM이 "아직 출시되지 않았다"고 말했는데,
 * 실제 evidence에는 이미 출시된 정보가 있으면 TEMPORAL_MISMATCH를 반환한다.
 * </p>
 */
@Component
public class TemporalConsistencyVerifier {

    /**
     * 단순한 시간 정합성 검증 결과.
     *
     * @param pass   검증 통과 여부
     * @param reason 실패한 경우 사유(디버깅용)
     */
    public record VerificationResult(boolean passed, String reason) {

        public static VerificationResult success() {
            return new VerificationResult(true, null);
        }

        public static VerificationResult fail(String reason) {
            return new VerificationResult(false, reason);
        }

        public boolean isPass() {
            return passed;
        }
    }

    /**
     * 답변과 evidence 리스트를 기반으로 시간 정합성을 점검한다.
     *
     * @param answer    최종 답변 텍스트
     * @param evidences evidence 텍스트 목록 (예: 검색 스니펫)
     * @param now       기준 시각 (보통 LocalDate.now())
     * @return 검증 결과
     */
    public VerificationResult verify(String answer, List<String> evidences, LocalDate now) {
        if (answer == null || answer.isBlank()) {
            return VerificationResult.success();
        }

        String lowerAnswer = answer.toLowerCase(Locale.ROOT);

        boolean saysNotReleased = containsAny(lowerAnswer,
                "아직 출시되지 않았", "출시 예정", "출시되지 않은",
                "will be released", "not yet released", "예정이다");

        if (!saysNotReleased) {
            return VerificationResult.success();
        }

        // evidence에 이미 출시됨을 암시하는 내용이 있는지 확인
        boolean hasPastRelease = evidences != null && evidences.stream().anyMatch(ev -> {
            if (ev == null) {
                return false;
            }
            String lower = ev.toLowerCase(Locale.ROOT);
            return (lower.contains("출시") || lower.contains("발매") || lower.contains("released"))
                    && (lower.contains("되었") || lower.contains("됐") || lower.contains("완료")
                            || lower.contains("했다") || lower.contains("했습니다"));
        });

        if (hasPastRelease) {
            return VerificationResult.fail(
                    "TEMPORAL_MISMATCH: answer says 'not released' but evidence suggests past release");
        }

        return VerificationResult.success();
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String t : tokens) {
            if (text.contains(t.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
