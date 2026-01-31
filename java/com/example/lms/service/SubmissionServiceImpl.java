// src/main/java/com/example/lms/service/SubmissionServiceImpl.java
package com.example.lms.service;

import com.example.lms.domain.Assignment;
import com.example.lms.domain.Student;
import com.example.lms.domain.Submission;
import com.example.lms.integrations.KakaoMessageService;
import com.example.lms.repository.AssignmentRepository;
import com.example.lms.repository.StudentRepository;
import com.example.lms.repository.SubmissionRepository;
import com.example.lms.service.SubmissionService;
import com.example.lms.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;



/**
 * SubmissionService 구현체 (최종 제출/임시 저장)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SubmissionServiceImpl implements SubmissionService {

    private final SubmissionRepository    repo;
    private final AssignmentRepository    assignmentRepo;
    private final StudentRepository       studentRepo;
    private final FileStorageService      storage;
    private final KakaoMessageService     kakao;

    /** 최종 제출 처리 (상태 = SUBMITTED) */
    @Override
    public void saveSubmission(Long assignmentId,
                               Long studentId,
                               MultipartFile file,
                               String clientIp) {

        Assignment asg = assignmentRepo.getReferenceById(assignmentId);
        Student    stu = studentRepo.getReferenceById(studentId);

        // 1) 파일 저장
        String savedUrl = storage.save(file, "assignment/" + assignmentId);

        // 2) DB 저장·갱신
        Submission sub = repo.findByAssignmentAndStudent(asg, stu)
                .orElseGet(() -> Submission.create(asg, stu));
        sub.complete(savedUrl, clientIp);
        repo.save(sub);

        // 3) 카카오 알림
        kakao.pushUrl(
                stu.getKakaoId(),
                "📑 과제 \"" + asg.getTitle() + "\" 제출이 완료되었습니다.",
                null
        );
    }

    /** 임시 저장 처리 (상태 = DRAFT) */
    @Override
    public void saveDraft(Long assignmentId,
                          Long studentId,
                          MultipartFile file) {

        Assignment asg = assignmentRepo.getReferenceById(assignmentId);
        Student    stu = studentRepo.getReferenceById(studentId);

        // 1) 파일 저장
        String savedUrl = storage.save(file, "draft/" + assignmentId);

        // 2) DB 저장·갱신
        Submission sub = repo.findByAssignmentAndStudent(asg, stu)
                .orElseGet(() -> Submission.create(asg, stu));
        sub.saveDraft(savedUrl);
        repo.save(sub);
    }
}