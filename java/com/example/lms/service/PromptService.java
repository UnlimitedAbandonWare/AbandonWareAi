// src/main/java/com/example/lms/service/PromptService.java
package com.example.lms.service;

import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PromptService {

    public static final String SYSTEM_PROMPT_KEY = "SYSTEM_PROMPT";

    private final ConfigurationSettingRepository configurationRepository;

    // DB에 값이 없을 경우를 대비한 기본 프롬프트 (application.properties에서 주입)
    @Value("${gpt.system.prompt}")
    private String defaultSystemPrompt;

    /**
     * 현재 적용된 시스템 프롬프트를 조회.
     * DB에 저장된 값이 있으면 그 값을, 없으면 프로퍼티의 기본값을 반환.
     */
    @Transactional(readOnly = true)
    public String getSystemPrompt() {
        return configurationRepository.findById(SYSTEM_PROMPT_KEY)
                .map(ConfigurationSetting::getSettingValue) // DB에 값이 있으면 그 값을 사용
                .orElse(defaultSystemPrompt); // 없으면 기본값 사용
    }

    /**
     * 새로운 시스템 프롬프트를 DB에 저장 (또는 갱신).
     */
    @Transactional
    public void saveSystemPrompt(String newPrompt) {
        ConfigurationSetting setting = configurationRepository.findById(SYSTEM_PROMPT_KEY)
                .orElse(new ConfigurationSetting(SYSTEM_PROMPT_KEY, null)); // 없으면 새 객체 생성

        setting.setSettingValue(newPrompt);
        configurationRepository.save(setting);
    }
}