// src/main/java/com/example/lms/service/correction/QueryCorrectionService.java
package com.example.lms.service.correction;


/** 사용자 입력 쿼리를 한 번만 교정(오타/띄어쓰기/간단 문법)하는 서비스 */
public interface QueryCorrectionService {
    String correct(String input);
}