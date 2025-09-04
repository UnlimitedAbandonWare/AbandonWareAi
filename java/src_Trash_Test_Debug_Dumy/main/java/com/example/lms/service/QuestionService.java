// src/main/java/com/example/lms/service/QuestionService.java
package com.example.lms.service;

import com.example.lms.domain.*;
import com.example.lms.repository.ChoiceRepository;
import com.example.lms.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional
public class QuestionService {

    private final QuestionRepository questionRepo;
    private final ChoiceRepository   choiceRepo;

    /* ────────────────────────────────
       1. 조회
       ──────────────────────────────── */
    @Transactional(readOnly = true)
    public List<Question> findByExam(Long examId) {
        return questionRepo.findByExamId(examId);
    }

    /* ────────────────────────────────
       2. 공통 문항 추가  ― 컨트롤러가 호출하는 메서드
          (MCQ/ESSAY 공용)
       ──────────────────────────────── */
    public Question addQuestion(Long        examId,
                                Question    questionParam,
                                String[]    choiceText,      // MCQ일 때만 전달
                                Boolean[]   choiceCorrect) { // MCQ일 때만 전달

        /* 2-1) Question 복제 + exam 주입  */
        Question q = Question.builder()
                .exam(new Exam(examId))          // 프록시 객체 주입
                .content(questionParam.getContent())
                .type(questionParam.getType())
                .answerKey(questionParam.getAnswerKey())
                .build();

        /* 2-2) MCQ라면 Choice 엔티티들을 생성 */
        if (choiceText != null && choiceCorrect != null) {
            IntStream.range(0, choiceText.length).forEach(i -> {
                Choice c = Choice.builder()
                        .question(q)                                    // 연관관계 주입
                        .text(choiceText[i])
                        .correct(Boolean.TRUE.equals(choiceCorrect[i]))
                        .build();
                q.addChoice(c);                                        // 편의 메서드
            });
        }

        /* 2-3) cascade = ALL 이라면 Choice 도 함께 저장됨 */
        return questionRepo.save(q);
    }

    /* ────────────────────────────────
       3. MCQ 추가 (배열 기반 – 레거시 API)
       ──────────────────────────────── */
    public Question addMCQ(Long     examId,
                           String   content,
                           String[] choiceTexts,
                           Boolean[] choiceCorrects,
                           String   answerKey) {

        Question q = Question.builder()
                .exam(new Exam(examId))
                .content(content)
                .type(QuestionType.MCQ)
                .answerKey(answerKey)
                .build();

        IntStream.range(0, choiceTexts.length).forEach(i -> {
            Choice c = Choice.builder()
                    .question(q)
                    .text(choiceTexts[i])
                    .correct(choiceCorrects != null
                            && choiceCorrects.length > i
                            && Boolean.TRUE.equals(choiceCorrects[i]))
                    .build();
            q.addChoice(c);
        });

        return questionRepo.save(q);
    }

    /* ────────────────────────────────
       4. MCQ 추가 (List 기반 – answerKey 로 정답 판별)
       ──────────────────────────────── */
    public Question addMCQ(Long         examId,
                           String       content,
                           List<String> choices,
                           String       answerKey) {

        Set<String> answerSet = new HashSet<>(Arrays.asList(answerKey.split(",")));

        Question q = Question.builder()
                .exam(new Exam(examId))
                .content(content)
                .type(QuestionType.MCQ)
                .answerKey(answerKey)
                .build();

        choices.stream()
                .map(text -> Choice.builder()
                        .question(q)
                        .text(text)
                        .correct(answerSet.contains(text))
                        .build())
                .forEach(q::addChoice);

        return questionRepo.save(q);
    }
}
