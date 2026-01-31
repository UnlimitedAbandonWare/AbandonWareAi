// src/main/java/com/example/lms/domain/Choice.java
package com.example.lms.domain;

import com.example.lms.domain.Question;
import jakarta.persistence.*;
import lombok.*;



@Entity
@Table(name = "choices")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Choice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 보통 답안 텍스트 길이 지정 */
// [수정 후]
    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /** 정답 여부 */
    private boolean correct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;
}