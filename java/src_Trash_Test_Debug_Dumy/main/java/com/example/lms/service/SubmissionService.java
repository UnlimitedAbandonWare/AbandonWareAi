// src/main/java/com/example/lms/service/SubmissionService.java
package com.example.lms.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 과제 제출 서비스
 */
public interface SubmissionService {

    /**
     * 최종 제출 (상태 = SUBMITTED)
     *
     * @param assignmentId 과제 ID
     * @param studentId    학생 ID
     * @param file         업로드할 파일
     * @param clientIp     제출자 IP (토큰 기반 업로드용)
     */
    void saveSubmission(Long assignmentId,
                        Long studentId,
                        MultipartFile file,
                        String clientIp);

    /**
     * 임시 저장 (상태 = DRAFT)
     *
     * @param assignmentId 과제 ID
     * @param studentId    학생 ID
     * @param file         업로드할 파일
     */
    void saveDraft(Long assignmentId,
                   Long studentId,
                   MultipartFile file);
}
