// 경로: com/example/lms/repository/HyperparameterRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Hyperparameter;
import org.springframework.data.jpa.repository.JpaRepository;



public interface HyperparameterRepository extends JpaRepository<Hyperparameter, String> {}