package com.example.lms.service.config;

import com.example.lms.domain.Hyperparameter;
import com.example.lms.repository.HyperparameterRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * DB에 저장된 하이퍼파라미터를 메모리 캐시에 로드하여 애플리케이션 전반에 제공합니다.
 * 동적 튜닝을 지원하며, 일반 조회 및 유효성 검증을 포함한 편의 메서드를 제공합니다.
 *
 * 하이퍼파라미터 조회 서비스.
 * Rerank 시너지 가중치 등 런타임 제어값을 제공합니다.
 */
@Service
@RequiredArgsConstructor
public class HyperparameterService {
    private static final Logger log = LoggerFactory.getLogger(HyperparameterService.class);

    private final HyperparameterRepository hyperparameterRepo;
    private final Map<String, Double> paramCache = new ConcurrentHashMap<>();

    @PostConstruct
    @Transactional(readOnly = true)
    public void init() {
        log.info("DB로부터 모든 하이퍼파라미터를 로딩합니다/* ... *&#47;");
        loadAll();
    }

    /**
     * 데이터베이스에서 모든 하이퍼파라미터를 다시 로드하여 캐시를 갱신합니다.
     */
    public void loadAll() {
        paramCache.clear();
        paramCache.putAll(hyperparameterRepo.findAll().stream()
                .collect(Collectors.toMap(Hyperparameter::getParamKey, Hyperparameter::getParamValue)));
        log.info("{}개의 하이퍼파라미터 로딩 완료.", paramCache.size());
    }

    /**
     * 파라미터 값을 Double 형으로 조회합니다. 없으면 기본값을 반환합니다.
     * @param key 조회할 파라미터 키
     * @param defaultValue 키가 존재하지 않을 때 반환될 기본값
     * @return 조회된 값 또는 기본값
     */
    public double getDouble(String key, double defaultValue) {
        return paramCache.getOrDefault(key, defaultValue);
    }

    /**
     * 파라미터 값을 Double 형으로 조회합니다. 없으면 Double.NaN을 반환합니다.
     * @param key 조회할 파라미터 키
     * @return 조회된 값 또는 Double.NaN
     */
    public double getDouble(String key) {
        return paramCache.getOrDefault(key, Double.NaN);
    }

    /**
     * 양수 값을 반환하며, DB에 없거나 0 이하일 경우 기본값을 사용합니다.
     * (e.g., temperature, learning_rate 등)
     * @param key 파라미터 키
     * @param defaultValue 폴백 기본값
     * @return 유효한 양수 파라미터 값
     */
    public double getPositiveDouble(String key, double defaultValue) {
        Double value = paramCache.get(key);
        return (value != null && value > 0.0) ? value : defaultValue;
    }

    /**
     * 0.0과 1.0 사이의 값을 반환하며, DB에 없거나 범위를 벗어날 경우 기본값을 사용합니다.
     * (e.g., epsilon, dropout_rate 등)
     * @param key 파라미터 키
     * @param defaultValue 폴백 기본값 (0.0 ~ 1.0)
     * @return 유효한 [0.0, 1.0] 범위 내 파라미터 값
     */
    public double getDoubleInRange01(String key, double defaultValue) {
        Double value = paramCache.get(key);
        return (value != null && value >= 0.0 && value <= 1.0) ? value : defaultValue;
    }

    /**
     * 파라미터 값을 int 형으로 조회합니다. 없으면 기본값을 반환합니다.
     * @param key 조회할 파라미터 키
     * @param defaultValue 키가 존재하지 않을 때 반환될 기본값
     * @return 조회된 값 또는 기본값의 정수 부분
     */
    public int getInt(String key, int defaultValue) {
        return paramCache.getOrDefault(key, (double) defaultValue).intValue();
    }

    /**
     * 파라미터 값을 boolean 형으로 조회합니다. 값이 0.5 이상이면 true로 간주합니다.
     * @param key 조회할 파라미터 키
     * @param defaultValue 키가 존재하지 않을 때 반환될 기본값
     * @return 조회된 값의 boolean 변환 결과 또는 기본값
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Double v = paramCache.get(key);
        if (v == null) return defaultValue;
        return v >= 0.5;
    }

    /**
     * 파라미터 값을 동적으로 설정하고, DB에 즉시 저장합니다.
     * @param key 설정할 파라미터 키
     * @param value 설정할 값
     */
    @Transactional
    public void set(String key, double value) {
        log.info("하이퍼파라미터 동적 튜닝: {} = {}", key, value);
        paramCache.put(key, value);
        Hyperparameter param = hyperparameterRepo.findById(key)
                .orElse(new Hyperparameter(key, value, "Dynamically tuned parameter."));
        param.setParamValue(value);
        hyperparameterRepo.save(param);
    }

    /** min/max로 클램프하여 증감(없으면 defaultStart에서 시작) */
    @Transactional
    public double adjust(String key, double delta, double min, double max, double defaultStart) {
        double cur = paramCache.getOrDefault(key, defaultStart);
        double next = Math.max(min, Math.min(max, cur + delta));
        set(key, next);
        return next;
    }

    /**
     * 리랭크 시너지 가중치 (런타임 튜닝 가능).
     * 우선순위: -Drerank.synergy-weight → env RERANK_SYNERGY_WEIGHT → 기본값 1.0
     */
    public double getRerankSynergyWeight() {
        String sys = System.getProperty("rerank.synergy-weight");
        // Avoid direct environment access; fall back to system properties instead
        String env = (sys == null ? System.getProperty("RERANK_SYNERGY_WEIGHT") : null);
        String raw = (sys != null ? sys : env);
        try {
            return (raw == null) ? 1.0 : Double.parseDouble(raw);
        } catch (Exception ignored) {
            return 1.0;
        }
    }
}