package com.example.lms.repository;

import com.example.lms.domain.ConfigurationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * ConfigurationSetting 엔티티를 위한 JpaRepository.
 * Primary Key 타입은 String 입니다.
 */
@Repository
public interface ConfigurationSettingRepository extends JpaRepository<ConfigurationSetting, String> {
}