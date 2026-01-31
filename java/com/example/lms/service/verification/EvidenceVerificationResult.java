// src/main/java/com/example/lms/service/verification/EvidenceVerificationResult.java
package com.example.lms.service.verification;

/**
 * Aggregated metrics from an evidence verification step.
 *
 * <p>이 클래스는 "얼마나 많은 증거가 모였는지"와 "증거의 신뢰도"를 숫자로
 * 요약해서 {@link DefaultEvidenceGate} 와 같은 게이트 계층에서 사용하기 위한
 * 단순 DTO 입니다.</p>
 */
public class EvidenceVerificationResult {

    /** 추출된 엔티티 중 RAG/KB에서 근거를 찾은 개수 */
    private int coveredEntityCount;

    /** 서로 독립적인 supporting 문서(혹은 스니펫) 개수 */
    private int supportingDocCount;

    /**
     * 전체 엔티티 대비 커버리지 (0.0 ~ 1.0).
     * <p>예: 10개의 엔티티 중 7개가 검증되었다면 0.7.</p>
     */
    private double coverageScore;

    /** 증거들끼리 서로 모순되는지가 여부 */
    private boolean contradictory;

    /** 전체 증거를 종합했을 때 "충분히 신뢰할만한지" 여부 */
    private boolean sufficientReliability;

    /** 어떤 형태로든 증거가 하나라도 존재하는지 여부 */
    private boolean anyEvidence;

    public EvidenceVerificationResult() {
        // zero-arg ctor for frameworks
    }

    public EvidenceVerificationResult(int coveredEntityCount,
                                      int supportingDocCount,
                                      double coverageScore,
                                      boolean contradictory,
                                      boolean sufficientReliability,
                                      boolean anyEvidence) {
        this.coveredEntityCount = coveredEntityCount;
        this.supportingDocCount = supportingDocCount;
        this.coverageScore = coverageScore;
        this.contradictory = contradictory;
        this.sufficientReliability = sufficientReliability;
        this.anyEvidence = anyEvidence;
    }

    public int getCoveredEntityCount() {
        return coveredEntityCount;
    }

    public int getSupportingDocCount() {
        return supportingDocCount;
    }

    public double getCoverageScore() {
        return coverageScore;
    }

    public boolean isContradictory() {
        return contradictory;
    }

    public boolean hasSufficientReliability() {
        return sufficientReliability;
    }

    public boolean hasAnyEvidence() {
        return anyEvidence;
    }

    // Fluent "with" helpers (optional but handy for future use)
    public EvidenceVerificationResult withCoveredEntityCount(int value) {
        this.coveredEntityCount = value;
        return this;
    }

    public EvidenceVerificationResult withSupportingDocCount(int value) {
        this.supportingDocCount = value;
        return this;
    }

    public EvidenceVerificationResult withCoverageScore(double value) {
        this.coverageScore = value;
        return this;
    }

    public EvidenceVerificationResult withContradictory(boolean value) {
        this.contradictory = value;
        return this;
    }

    public EvidenceVerificationResult withSufficientReliability(boolean value) {
        this.sufficientReliability = value;
        return this;
    }

    public EvidenceVerificationResult withAnyEvidence(boolean value) {
        this.anyEvidence = value;
        return this;
    }
}
