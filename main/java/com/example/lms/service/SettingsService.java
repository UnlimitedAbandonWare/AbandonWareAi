// 野껋럥以? src/main/java/com/example/lms/service/SettingsService.java
package com.example.lms.service;

import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;




/**
 * ?袁⑸열 Key-Value ??쇱젟 ???關??
 *  ?? ??μ뵬 ????: save(key, value)
 *  ?? ??⑦겣 ????: saveAllSettings(Map) ?爰용? ?⑥눊援???已??紐낆넎??saveAll(Map) ?곕떽?
 *  ?? ?袁⑷퍥 鈺곌퀬??: getAllSettings()
 */
@Service
@RequiredArgsConstructor
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final ConfigurationSettingRepository settingRepo;

    /* ?????????? Key ?怨몃땾 (?뚢뫂?껅에?살쑎?癒?퐣 域밸챶?嚥????? ?????????? */
    public static final String KEY_SYSTEM_PROMPT      = "SYSTEM_PROMPT";
    public static final String KEY_TEMPERATURE        = "TEMPERATURE";
    public static final String KEY_TOP_P              = "TOP_P";
    public static final String KEY_FREQUENCY_PENALTY  = "FREQUENCY_PENALTY";
    public static final String KEY_PRESENCE_PENALTY   = "PRESENCE_PENALTY";
    public static final String KEY_OPENAI_MODEL       = "OPENAI_MODEL";
    public static final String KEY_FINE_TUNED_MODEL   = "FINE_TUNED_MODEL";

    /* ?????????? 疫꿸퀡??첎?(application.properties ?癒?퐣 雅뚯눘?? ?????????? */
    @Value("${gpt.system.prompt.default:You are a helpful assistant.}")
    private String defaultSystemPrompt;

    @Value("${openai.api.model.default:${llm.chat-model:gemma4:26b}}")
    private String defaultModel;

    @Value("${openai.api.temperature.default:${llm.chat.temperature:0.3}}")
    private String defaultTemperature;

    @Value("${openai.api.top-p.default:1.0}")
    private String defaultTopP;

    @Value("${openai.api.frequency-penalty.default:0.0}")
    private String defaultFreqPenalty;

    @Value("${openai.api.presence-penalty.default:0.0}")
    private String defaultPresPenalty;

    /* ?癒λ름?癒λ름?癒λ름?癒λ름?癒λ름?癒λ름??疫꿸퀡??CRUD ?癒λ름?癒λ름?癒λ름?癒λ름?癒λ름?癒λ름??*/

    /** ??μ뵬 Key-Value ??녾퐣??*/
@CacheEvict(cacheNames = "settings", allEntries = true)
    @Transactional
    public void save(String key, String value) {
        if (key == null || value == null) return;
        ConfigurationSetting entity = settingRepo.findById(key)
                .orElse(new ConfigurationSetting(key, null));
        entity.setSettingValue(value);
        settingRepo.save(entity);
        log.debug("Setting saved key={} valueHash={} valueLength={}",
                key, com.example.lms.trace.SafeRedactor.hashValue(value), value.length());
    }

    /** ?????뚢뫂?껅에?살쑎揶쎛 ?紐꾪뀱??롫쐲 筌롫뗄苑????已??醫???*/
@CacheEvict(cacheNames = "settings", allEntries = true)
    @Transactional
    public void saveAll(Map<String, String> kv) {
        saveAllSettings(kv);               // ??? 筌롫뗄苑??뺤쨮 ?袁⑹뿫
    }

    /** ??쇱젫 ??⑦겣 ????嚥≪뮇彛?*/
@CacheEvict(cacheNames = "settings", allEntries = true)
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
        log.debug("??⑦겣 ?????袁⑥┷ ??{}", kv.keySet());
    }

    /** ??μ뵬 鈺곌퀬??(??곸몵筌?null) */
    @Cacheable(cacheNames = "settings", key = "'k:' + #key", unless = "#result == null")
    @Transactional(readOnly = true)
    public String get(String key) {
        return settingRepo.findById(key)
                .map(ConfigurationSetting::getSettingValue)
                .orElse(null);
    }

    /** ??????筌롫뗄苑??뺣즲 癰귣벀??*/
@CacheEvict(cacheNames = "settings", allEntries = true)
    @Transactional
    public void delete(String key) {
        settingRepo.deleteById(key);
        log.debug("Setting ??????{}", key);
    }

    /** ?袁⑷퍥 鈺곌퀬??Map) - 疫꿸퀡??첎?癰귣쵑鍮 */
@Cacheable(cacheNames = "settings", key = "'all'")
    @Transactional(readOnly = true)
    public Map<String, String> getAllSettings() {
        // 1) DB values
        Map<String, String> db = settingRepo.findAll().stream()
                .collect(Collectors.toMap(ConfigurationSetting::getSettingKey,
                        ConfigurationSetting::getSettingValue));

        // 2) configured defaults
        Map<String, String> defaults = new ConcurrentHashMap<>();
        defaults.put(KEY_SYSTEM_PROMPT,     defaultSystemPrompt);
        defaults.put(KEY_TEMPERATURE,       defaultTemperature);
        defaults.put(KEY_TOP_P,             defaultTopP);
        defaults.put(KEY_FREQUENCY_PENALTY, defaultFreqPenalty);
        defaults.put(KEY_PRESENCE_PENALTY,  defaultPresPenalty);
        defaults.put(KEY_OPENAI_MODEL,      defaultModel);
        defaults.put(KEY_FINE_TUNED_MODEL,  "");

        // 3) 癰귣쵑鍮
        defaults.putAll(db);
        return defaults;
    }
}
