// 경로: com/example/lms/repository/ConfigRepository.java
package com.example.lms.repository;

import org.springframework.stereotype.Repository;

import java.util.Optional;

// 데모용 임시 구현. 실제로는 JPA Repository를 사용해야 합니다.
@Repository
public class ConfigRepository {
    public Optional<Double> findDouble(String key) {
        // TODO: DB에서 설정값 조회
        return Optional.empty();
    }
    public void save(String key, Double value) {
        // TODO: DB에 설정값 저장
    }
}