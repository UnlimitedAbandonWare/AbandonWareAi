package com.example.lms.repository;

import com.example.lms.entity.AppConfig;
import org.springframework.data.repository.CrudRepository;

public interface AppConfigRepository extends CrudRepository<AppConfig, Long> {
    // 기본 defaultModel 조회/수정 메서드는 CrudRepository에 포함되어 있습니다.
}
