
// src/main/java/com/example/lms/repository/ChoiceRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Choice;
import com.example.lms.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;




@Repository
public interface ChoiceRepository extends JpaRepository<Choice, Long> {
    List<Choice> findByQuestion(Question question);
}