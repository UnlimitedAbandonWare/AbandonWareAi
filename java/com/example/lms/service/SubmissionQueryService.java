// src/main/java/com/example/lms/service/SubmissionQueryService.java
package com.example.lms.service;

import com.example.lms.domain.Submission;
import java.util.Map;
import java.util.Optional;




/**
 * 조회 전용 제출 서비스 인터페이스
 */
public interface SubmissionQueryService {

    /** 과제·학생별 제출 여부 확인 */
    boolean exists(Long assignmentId, Long studentId);

    /** 과제·학생별 제출 내역 조회 (없으면 empty) */
    Optional<Submission> find(Long assignmentId, Long studentId);

    /** 학생별 과제 제출 상태 맵 (과제ID → “제출완료” or null) */
    Map<Long, String> statusMapByStudent(Long stuId);

    /** 과제 제출을 위한 업로드 토큰 발급 */
    String issueUploadToken(Long asgId, Long stuId);

}