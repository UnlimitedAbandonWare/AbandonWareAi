package com.example.lms.repository;

import com.example.lms.entity.ModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * JPA Repository for ModelEntity.
 * PK 타입이 String(modelId) 인 점에 유의하세요.
 */
public interface ModelEntityRepository extends JpaRepository<ModelEntity, String> {

    /**
     * 특정 소유자(owner)가 만든 모델 조회
     * 메서드명 속 property(owner)가 실제 엔티티 필드명(owner)과 일치해야 자동 쿼리가 생성됩니다.
     */
    List<ModelEntity> findAllByOwner(String owner);

    // JPQL 직접 작성 예시:
    // @Query("SELECT m FROM ModelEntity m WHERE m.owner = :owner")
    // List<ModelEntity> findAllByOwner(@Param("owner") String owner);
}
