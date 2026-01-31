// src/main/java/com/example/lms/service/SubmissionQueryServiceImpl.java
package com.example.lms.service;

import com.example.lms.domain.Submission;
import com.example.lms.repository.SubmissionRepository;
import com.example.lms.service.SubmissionQueryService;
import com.example.lms.service.UploadTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;




/**
 * SubmissionQueryService 구현체
 */
@Service
@RequiredArgsConstructor
public class SubmissionQueryServiceImpl implements SubmissionQueryService {
    private final SubmissionRepository repo;
    private final UploadTokenService tokenSvc;

    @Override
    public boolean exists(Long assignmentId, Long studentId) {
        return repo.existsByAssignmentIdAndStudentId(assignmentId, studentId);
    }

    @Override
    public Optional<Submission> find(Long assignmentId, Long studentId) {
        return repo.findByAssignmentIdAndStudentId(assignmentId, studentId);
    }

    @Override
    public Map<Long, String> statusMapByStudent(Long stuId) {
        return repo.findAll().stream()
                .filter(s -> s.getStudent().getId().equals(stuId))
                .collect(Collectors.toMap(
                        s -> s.getAssignment().getId(),
                        s -> "제출완료"
                ));
    }

    @Override
    public String issueUploadToken(Long asgId, Long stuId) {
        // UploadTokenService 를 통해 30분 유효 토큰 발급
        var asg = new com.example.lms.domain.Assignment(asgId);
        var stu = new com.example.lms.domain.Student(stuId);
        return tokenSvc.issue(asg, stu, java.time.Duration.ofMinutes(30)).getToken();
    }
}