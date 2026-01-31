// src/main/java/com/example/lms/repository/TrainingJobRepository.java
package com.example.lms.repository;

import com.example.lms.domain.TrainingJob;
import org.springframework.data.jpa.repository.JpaRepository;



public interface TrainingJobRepository extends JpaRepository<TrainingJob,Long>{}