package com.example.lms.repository;

import com.example.lms.entity.ModelEntity;
import org.springframework.data.repository.CrudRepository;

public interface ModelRepository extends CrudRepository<ModelEntity, String> {
    // 필요시 커스텀 쿼리 메서드 추가
}
