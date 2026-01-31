
// src/main/java/com/example/lms/repository/QuestionRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Question;
import com.example.lms.domain.Exam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;




@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findByExam(Exam exam);
    List<Question> findByExamId(Long examId);
}