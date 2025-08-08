// 경로: com.example/lms/service/config/HyperparameterService.java
package com.example.lms.service.config;

import com.example.lms.domain.Hyperparameter;
import com.example.lms.repository.HyperparameterRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HyperparameterService {

    private final HyperparameterRepository hyperparameterRepo;
    private final Map<String, Double> paramCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("DB로부터 모든 하이퍼파라미터를 로딩합니다...");
        loadAll();
    }

    public void loadAll() {
        paramCache.clear();
        paramCache.putAll(hyperparameterRepo.findAll().stream()
                .collect(Collectors.toMap(Hyperparameter::getParamKey, Hyperparameter::getParamValue)));
        log.info("{}개의 하이퍼파라미터 로딩 완료.", paramCache.size());
    }

    public double getDouble(String key, double defaultValue) {
        return paramCache.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return paramCache.getOrDefault(key, (double) defaultValue).intValue();
    }

    @Transactional
    public void set(String key, double value) {
        paramCache.put(key, value);
        Hyperparameter param = hyperparameterRepo.findById(key)
                .orElse(new Hyperparameter(key, value, "Dynamically tuned parameter."));
        param.setParamValue(value);
        hyperparameterRepo.save(param);
    }
}