package com.example.lms.service.disambiguation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;



/**
 * 사용자의 모호한 질의를 재작성하고 해소한 결과를 담는 DTO입니다.
 * LLM이 생성한 JSON 응답을 이 객체로 매핑합니다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true) // LLM이 예상치 못한 필드를 추가해도 오류 없이 파싱
public class DisambiguationResult {

    /**
     * 모호하다고 판단된 원본 텍스트의 일부입니다.
     * 예: "유리"
     */
    private String ambiguousTerm;

    /**
     * LLM이 판별한 사용자의 최종 의도입니다.
     * 예: "유리(GIRL·FRIEND)에 대한 정보 검색"
     */
    private String resolvedIntent;

    /**
     * 모호성이 해소되어 검색에 더 적합하도록 재작성된 쿼리입니다.
     * 예: "걸그룹 여자친구 유리"
     */
    private String rewrittenQuery;

    /**
     * 판별 결과에 대한 LLM의 자신감 수준입니다.
     * ( "low" | "medium" | "high" 또는 자유 텍스트 )
     */
    private String confidence;

    /**
     * 판별 결과에 대한 신뢰도 점수입니다. (0.0 ~ 1.0)
     */
    private Double score;

    /**
     * 이 판별 결과를 신뢰하고 재작성된 쿼리를 사용할지 결정하는 운영 기준 메서드입니다.
     *
     * @return 신뢰도 점수가 0.65 이상이거나, confidence 필드에 "high" 계열의 텍스트가 포함되면 true를 반환합니다.
     */
    public boolean isConfident() {
        if (score != null) {
            return score >= 0.65;
        }
        if (confidence == null) {
            return false;
        }
        String c = confidence.trim().toLowerCase();
        return c.contains("high") || c.contains("confident") || c.contains("sure");
    }
}