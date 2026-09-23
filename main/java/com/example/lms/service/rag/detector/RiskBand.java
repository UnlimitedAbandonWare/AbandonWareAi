// src/main/java/com/example/lms/service/rag/detector/RiskBand.java
package com.example.lms.service.rag.detector;

/**
 * Coarse-grained risk band for a user query.
 *
 * <ul>
 *   <li>{@link #HIGH}   – 의료, 법률, 재무 투자 등 고위험 도메인</li>
 *   <li>{@link #NORMAL} – 일반적인 정보 검색</li>
 *   <li>{@link #LOW}    – 게임, 서브컬쳐, 잡담 등 비교적 저위험 도메인</li>
 * </ul>
 *
 * <p>세부 정책은 각 서비스(예: EvidenceGate, DomainWhitelist)가 이 값을
 * 해석하는 방식에 따라 달라집니다.</p>
 */
public enum RiskBand {
    HIGH,
    NORMAL,
    LOW
}
