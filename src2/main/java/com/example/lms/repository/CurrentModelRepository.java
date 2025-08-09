package com.example.lms.repository;

import com.example.lms.entity.CurrentModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CurrentModelRepository extends JpaRepository<CurrentModel, Long> {

    /** 편의 메서드 – 단일 행만 존재하므로 항상 ID=1 조회 */
    default Optional<CurrentModel> findSingleton() {
        return findById(1L);
    }
}
