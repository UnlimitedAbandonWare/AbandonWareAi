// src/main/java/com/example/lms/domain/Question.java
package com.example.lms.domain;

import com.example.lms.domain.Choice;
import com.example.lms.domain.Exam;
import com.example.lms.domain.QuestionType;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;




/**
 * 시험(Exam)에 속한 문항(Question) 엔티티
 */
@Entity
@Table(name = "questions")
@Getter
@Setter                                 // ← 서비스에서 setXxx() 호출 가능
@Builder                                 // ← Question.builder() 지원
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 기본 생성자
@AllArgsConstructor
public class Question {

    /* ──────────── 기본 키 ──────────── */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ──────────── 연관 관계 ──────────── */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;                               // ★ 필드명 exam

    /* ──────────── 속성 ──────────── */
    @Column(length = 1000, nullable = false)
    private String content;                          // ★ text → content

    @Enumerated(EnumType.STRING)
    @Column(length = 50,  nullable = false)
    private QuestionType type;                       // MCQ, ESSAY 등

    @Column(length = 1000)                           // MCQ 옵션 ‘|’ 구분
    private String options;

    @Column(length = 1000)
    private String answerKey;                        // ★ answer → answerKey

    /* ──────────── 선택지(Choice) ──────────── */
    @Builder.Default
    @OneToMany(mappedBy = "question",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<Choice> choices = new ArrayList<>();

    /* ──────────── 편의 메서드 ──────────── */
    public void addChoice(Choice c) {
        choices.add(c);
        c.setQuestion(this);
    }

    public void removeChoice(Choice c) {
        choices.remove(c);
        c.setQuestion(null);
    }

    /* ──────────── id 전용 생성자 ──────────── */
    public Question(Long id) { this.id = id; }
}