package com.example.lms.service.verification;


/**
 * 팩트 검증 1단계 분류기의 결과 값.
 *
 * <ul>
 *   <li><b>PASS</b> - 초안 그대로 사용 가능</li>
 *   <li><b>CORRECTED</b> - 핵심 내용은 맞지만 부분 수정 필요</li>
 *   <li><b>INSUFFICIENT</b> - 컨텍스트로 검증 불가</li>
 *   <li><b>UNKNOWN</b> - 모델 응답 파싱 실패</li>
 * </ul>
 */
public enum FactVerificationStatus {

    /** 초안 그대로 사용 가능 */
    PASS,

    /** 핵심 내용은 맞지만 부분 수정 필요 */
    CORRECTED,

    /** 컨텍스트로 검증 불가 */
    INSUFFICIENT,

    /** 모델 응답 파싱 실패 또는 분류 불가 */
    UNKNOWN
}