package com.example.lms.service.correction;

/** 사용자 입력 쿼리를 한 번만 교정(오타/띄어쓰기/간단 문법)하는 서비스 */
public interface QueryCorrectionService {
    /**
     * @param input 사용자가 보낸 원문
     * @return 교정된 문자열; 교정 불가/비활성 시 원문 그대로 반환
     */
    String correct(String input);
}
