package com.example.lms.service;

import com.example.lms.domain.*;
import com.example.lms.dto.AssignmentDTO;
import com.example.lms.repository.*;
import com.example.lms.service.AssignmentQueryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.util.FileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 과제 관련 조회, 저장, 제출, 채점 및 기억 강화 로직을 모두 처리하는 통합 서비스
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AssignmentService implements AssignmentQueryService {

    // --- 의존성 주입 ---
    private final AssignmentRepository   assignmentRepo;
    private final SubmissionRepository   submissionRepo;
    private final GradeRepository        gradeRepo;
    private final FileStorage            fileStorage;
    private final MemoryReinforcementService memoryReinforcementService; // 기억 강화 서비스

    // ─────────── AssignmentQueryService 구현 (학생용 조회) ───────────

    /**
     * 학생별 전체 과제 목록과 학생의 제출 여부를 함께 조회합니다.
     * @param stuId 학생 ID
     * @return 제출 여부가 포함된 과제 DTO 목록
     */
    @Override
    @Transactional(readOnly = true)
    public List<AssignmentDTO> findForStudent(Long stuId) {
        return assignmentRepo.findAll().stream()
                .map(a -> {
                    boolean submitted = submissionRepo
                            .existsByAssignmentIdAndStudentId(a.getId(), stuId);
                    return AssignmentDTO.of(a, submitted);
                })
                .collect(Collectors.toList());
    }

    /**
     * 과제 한 건의 상세 정보를 조회합니다.
     * @param asgId 과제 ID
     * @return 과제 DTO
     */
    @Override
    @Transactional(readOnly = true)
    public AssignmentDTO findById(Long asgId) {
        Assignment a = assignmentRepo.findById(asgId)
                .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다: " + asgId));
        return AssignmentDTO.of(a, false);
    }

    // ─────────── 과제 저장 및 제출 로직 (핵심 기능) ───────────

    /**
     * 과제를 임시 저장하거나 업데이트합니다.
     * 최종 제출 시(temporary=false)에는 기억 강화 로직을 호출합니다.
     *
     * @param assignmentId 과제 ID
     * @param studentId 학생 ID
     * @param file 첨부 파일
     * @param answerText 학생이 입력한 텍스트 답변
     * @param temporary 임시 저장 여부 (true: 임시 저장, false: 최종 제출의 일부)
     * @return 저장된 Submission 객체
     */
    public Submission saveSubmission(Long assignmentId,
                                     Long studentId,
                                     MultipartFile file,
                                     String answerText,
                                     boolean temporary) throws IOException {
        Assignment assignment = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다: " + assignmentId));
        Student student = new Student(studentId);

        // 기존 제출 내역이 있으면 가져오고, 없으면 새로 생성
        Submission sub = submissionRepo
                .findByAssignmentAndStudent(assignment, student)
                .orElseGet(() -> Submission.create(assignment, student));

        sub.setAnswerText(answerText);
        sub.setTemporary(temporary);
        sub.setStatus(temporary ? SubmissionStatus.DRAFT : SubmissionStatus.SAVED);
        sub.setSubmittedAt(LocalDateTime.now());

        if (file != null && !file.isEmpty()) {
            String savedUrl = fileStorage.save(file);
            sub.setFileUrl(savedUrl);
        }

        // ✨ [기억 강화 로직]
        // 최종 제출이고, 텍스트 답변이 존재할 경우에만 시스템의 기억을 강화합니다.
        if (!temporary && StringUtils.hasText(answerText)) {
            memoryReinforcementService.reinforceMemoryWithText(answerText);
        }

        return submissionRepo.save(sub);
    }

    /**
     * 과제를 최종 제출하고, 자동 채점 및 성적 반영까지 수행합니다.
     * 내부적으로 saveSubmission을 호출하여 기억 강화 로직을 트리거합니다.
     *
     * @param assignmentId 과제 ID
     * @param studentId 학생 ID
     * @param file 첨부 파일
     * @param answerText 학생이 입력한 텍스트 답변
     * @return 모든 처리가 완료된 Submission 객체
     */
    public Submission submit(Long assignmentId,
                             Long studentId,
                             MultipartFile file,
                             String answerText) throws IOException {
        // 1. 저장 로직 재사용 (여기서 기억 강화 로직이 호출됩니다)
        Submission sub = saveSubmission(
                assignmentId, studentId,
                file, answerText,
                false // 최종 제출이므로 temporary=false
        );

        // 2. 최종 제출 상태로 변경
        sub.setStatus(SubmissionStatus.SUBMITTED);
        sub.setSubmittedAt(LocalDateTime.now());
        sub = submissionRepo.save(sub);

        // 3. 자동 채점 (간단한 예시: 텍스트 존재 시 100점)
        double autoScore = StringUtils.hasText(answerText) ? 100.0 : 0.0;
        sub.setAutoScore(autoScore);
        sub.setFinalScore(autoScore);
        sub.setStatus(SubmissionStatus.GRADED);
        submissionRepo.save(sub);

        // 4. 성적(Grade) 테이블에 최종 점수 반영
        Course  course  = sub.getAssignment().getCourse();
        Student student = sub.getStudent();
        Grade grade = gradeRepo.findByCourseAndStudent(course, student)
                .orElse(new Grade()); // 기존 성적이 없으면 새로 생성

        grade.setCourse(course);
        grade.setStudent(student);
        grade.setScore(sub.getFinalScore());
        gradeRepo.save(grade);

        return sub;
    }
}
