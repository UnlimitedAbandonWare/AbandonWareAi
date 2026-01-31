// src/main/java/com/example/lms/domain/Submission.java
package com.example.lms.domain;

import com.example.lms.domain.Assignment;
import com.example.lms.domain.Student;
import com.example.lms.domain.SubmissionStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;



/**
 * 과제 제출(또는 임시 저장) 엔티티
 */
@Entity
@Table(
        name = "submissions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"assignment_id", "student_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Submission {

    /** PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 과제(FK) */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    private Assignment assignment;

    /** 학생(FK) */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /** 객관식·단답형 답안 */
    @Column(length = 2000)
    private String answerText;

    /** 서술형·에세이 답안 */
    @Column(length = 2000)
    private String content;

    /** 첨부 파일 경로(URL) */
    private String fileUrl;

    /** 제출 시점 IP */
    private String submitIp;

    /** 제출(저장) 일시 */
    private LocalDateTime submittedAt;

    /** 마지막 갱신 일시 */
    private LocalDateTime updatedAt;

    /** 임시 저장 여부 */
    private boolean temporary;

    /** 저장 상태 (예: DRAFT, SAVED, SUBMITTED, GRADED) */
    @Enumerated(EnumType.STRING)
    private SubmissionStatus status;

    /** 자동 채점 점수 */
    private Double autoScore;

    /** 최종 점수 */
    private Double finalScore;

    /**
     * 새 제출 엔티티 생성 (초기 상태: DRAFT, temporary=true)
     */
    public static Submission create(Assignment assignment, Student student) {
        Submission s = new Submission();
        s.assignment  = assignment;
        s.student     = student;
        s.updatedAt   = LocalDateTime.now();
        s.temporary   = true;
        s.status      = SubmissionStatus.DRAFT;
        return s;
    }

    /**
     * 실제 제출 처리
     * @param url 파일 저장 URL
     * @param ip  제출자 IP
     */
    public void complete(String url, String ip) {
        this.fileUrl     = url;
        this.submitIp    = ip;
        this.submittedAt = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
        this.temporary   = false;
        this.status      = SubmissionStatus.SAVED;
    }

    /**
     * 임시 저장 처리
     * @param url 파일 저장 URL
     */
    public void saveDraft(String url) {
        this.fileUrl   = url;
        this.updatedAt = LocalDateTime.now();
        this.temporary = true;
        this.status    = SubmissionStatus.DRAFT;
    }
}