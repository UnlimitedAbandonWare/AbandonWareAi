// src/main/java/com/example/lms/domain/enums/RulePhase.java
package com.example.lms.domain.enums;

/** 규칙이 적용되는 단계 */
public enum RulePhase {
    /** 모델에 보내기 전 전처리 */
    PRE,
    /** 모델 응답을 받아서 후처리 */
    POST
}
