// src/main/java/com/example/lms/repository/ModelInfoRepository.java
package com.example.lms.repository;

import com.example.lms.model.ModelInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelInfoRepository extends JpaRepository<ModelInfo, String> {

    /**
     * 최신 생성일 순으로 전체 모델 리스트를 가져옵니다.
     * (ModelInfo.created 필드가 UNIX timestamp 인 경우)
     */
    List<ModelInfo> findAllByOrderByCreatedDesc();

    /**
     * family(모델군) 으로 필터링 하고 싶을 때 예시
     */
    List<ModelInfo> findByFamily(String family);
}
