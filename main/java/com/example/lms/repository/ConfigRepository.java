// 경로: com/example/lms/repository/ConfigRepository.java
package com.example.lms.repository;

import org.springframework.stereotype.Repository;
import java.util.Optional;




// 데모용 임시 구현. 실제로는 JPA Repository를 사용해야 합니다.
@Repository
public class ConfigRepository {
    public Optional<Double> findDouble(String key) {
        // Implementation shim: load configuration values from the database.
        return Optional.empty();
    }
    public void save(String key, Double value) {
        // Implementation shim: persist configuration values to the database.
    }
}