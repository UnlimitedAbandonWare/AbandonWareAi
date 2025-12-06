package com.example.lms.repository;

import com.example.lms.domain.Setting;
import org.springframework.data.jpa.repository.JpaRepository;



public interface SettingRepository extends JpaRepository<Setting, String> {}