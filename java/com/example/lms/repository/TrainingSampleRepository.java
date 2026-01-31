package com.example.lms.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


import com.example.lms.domain.TranslationSample;      // ✅ 수정

@Repository
public interface TrainingSampleRepository
        extends JpaRepository<TranslationSample, Long> { }  // ✅ 수정