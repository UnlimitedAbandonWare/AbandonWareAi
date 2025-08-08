// 경로: src/main/java/com/example/lms/service/SettingsService.java
package com.example.lms.service;

import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 전역 Key-Value 설정 저장소.
 *  ─ 단일 저장 : save(key, value)
 *  ─ 일괄 저장 : saveAllSettings(Map)  ← 과거 이름 호환용 saveAll(Map) 추가
 *  ─ 전체 조회 : getAllSettings()
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final ConfigurationSettingRepository settingRepo;

    /* ───── Key 상수 (컨트롤러에서 그대로 사용) ───── */
    public static final String KEY_SYSTEM_PROMPT      = "SYSTEM_PROMPT";
    public static final String KEY_TEMPERATURE        = "TEMPERATURE";
    public static final String KEY_TOP_P              = "TOP_P";
    public static final String KEY_FREQUENCY_PENALTY  = "FREQUENCY_PENALTY";
    public static final String KEY_PRESENCE_PENALTY   = "PRESENCE_PENALTY";
    public static final String KEY_OPENAI_MODEL       = "OPENAI_MODEL";
    public static final String KEY_FINE_TUNED_MODEL   = "FINE_TUNED_MODEL";

    /* ───── 기본값 (application.properties 에서 주입) ───── */
    @Value("${gpt.system.prompt.default:You are a helpful assistant.}")
    private String defaultSystemPrompt;

    @Value("${openai.api.model.default:gpt-4o-mini}")
    private String defaultModel;

    @Value("${openai.api.temperature.default:0.7}")
    private String defaultTemperature;

    @Value("${openai.api.top-p.default:1.0}")
    private String defaultTopP;

    @Value("${openai.api.frequency-penalty.default:0.0}")
    private String defaultFreqPenalty;

    @Value("${openai.api.presence-penalty.default:0.0}")
    private String defaultPresPenalty;

    /* ═════════════ 기본 CRUD ═════════════ */

    /** 단일 Key-Value 업서트 */
    @Transactional
    public void save(String key, String value) {
        if (key == null || value == null) return;
        ConfigurationSetting entity = settingRepo.findById(key)
                .orElse(new ConfigurationSetting(key, null));
        entity.setSettingValue(value);
        settingRepo.save(entity);
        log.debug("Setting 저장 → {} = {}", key, value);
    }

    /** ★ 옛 컨트롤러가 호출하던 메서드 이름 유지용 */
    @Transactional
    public void saveAll(Map<String, String> kv) {
        saveAllSettings(kv);               // 내부 메서드로 위임
    }

    /** 실제 일괄 저장 로직 */
    @Transactional
    public void saveAllSettings(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) return;

        List<ConfigurationSetting> entities = kv.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> settingRepo.findById(e.getKey())
                        .orElse(new ConfigurationSetting(e.getKey(), null)))
                .peek(cs -> cs.setSettingValue(kv.get(cs.getSettingKey())))
                .collect(Collectors.toList());

        settingRepo.saveAll(entities);
        log.debug("일괄 저장 완료 → {}", kv.keySet());
    }

    /** 단일 조회 (없으면 null) */
    @Transactional(readOnly = true)
    public String get(String key) {
        return settingRepo.findById(key)
                .map(ConfigurationSetting::getSettingValue)
                .orElse(null);
    }

    /** ★ 삭제 메서드도 복구 */
    @Transactional
    public void delete(String key) {
        settingRepo.deleteById(key);
        log.debug("Setting 삭제 → {}", key);
    }

    /** 전체 조회(Map) – 기본값 병합 */
    @Transactional(readOnly = true)
    public Map<String, String> getAllSettings() {
        // 1) DB 값
        Map<String, String> db = settingRepo.findAll().stream()
                .collect(Collectors.toMap(ConfigurationSetting::getSettingKey,
                        ConfigurationSetting::getSettingValue));

        // 2) 기본값
        Map<String, String> defaults = new ConcurrentHashMap<>();
        defaults.put(KEY_SYSTEM_PROMPT,     defaultSystemPrompt);
        defaults.put(KEY_TEMPERATURE,       defaultTemperature);
        defaults.put(KEY_TOP_P,             defaultTopP);
        defaults.put(KEY_FREQUENCY_PENALTY, defaultFreqPenalty);
        defaults.put(KEY_PRESENCE_PENALTY,  defaultPresPenalty);
        defaults.put(KEY_OPENAI_MODEL,      defaultModel);
        defaults.put(KEY_FINE_TUNED_MODEL,  "");

        // 3) 병합
        defaults.putAll(db);
        return defaults;
    }
}
