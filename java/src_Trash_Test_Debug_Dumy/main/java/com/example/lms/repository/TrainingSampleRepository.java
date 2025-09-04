package com.example.lms.repository;

import com.example.lms.domain.TranslationSample;      // ✅ 수정
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrainingSampleRepository
        extends JpaRepository<TranslationSample, Long> { }  // ✅ 수정
