// src/main/java/com/example/lms/service/verification/FactVerificationStatus.java
package com.example.lms.service.verification;
/**
 * 1단계 분류기의 결과 값.<br>
 * <b>PASS</b> – 초안 그대로 사용 가능<br>
 * <b>CORRECTED</b> – 부분 수정 필요<br>
 * <b>INSUFFICIENT</b> – 컨텍스트 부족<br>
 * <b>UNKNOWN</b> – 모델 응답 파싱 실패
 */
/** 1단계 분류기의 결과 값 */
public enum FactVerificationStatus {
    PASS,          // 초안 그대로 사용 가능
    CORRECTED,     // 핵심 내용은 맞지만 부분 수정 필요
    INSUFFICIENT,   // 컨텍스트로 검증 불가
    UNKNOWN
}
