package com.example.lms.api;

import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
// ✅ 클래스 이름을 파일명과 동일하게 SettingsController로 수정했습니다.
public class SettingsController {

    private final ConfigurationSettingRepository configurationSettingRepository;

    /**
     * 모든 설정을 'configuration_settings' 테이블에서 조회합니다.
     * 이제 UI를 열 때 항상 최신 설정값을 불러옵니다.
     *
     * @return DB에 저장된 모든 설정을 Key-Value 형태의 Map으로 반환
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> getAllSettings() {
        List<ConfigurationSetting> settings = configurationSettingRepository.findAll();

        Map<String, String> settingMap = settings.stream()
                .collect(Collectors.toMap(
                        ConfigurationSetting::getSettingKey,
                        ConfigurationSetting::getSettingValue
                ));

        return ResponseEntity.ok(settingMap);
    }

    /**
     * 받은 설정들을 'configuration_settings' 테이블에 저장(업데이트)합니다.
     * 조회와 저장이 동일한 테이블을 사용하도록 통일되었습니다.
     *
     * @param settingsToSave 프론트엔드에서 받은 설정값 Map
     * @return 성공 메시지
     */
    @PostMapping
    @Transactional // 여러 건의 저장을 하나의 트랜잭션으로 처리
    public ResponseEntity<Map<String, String>> saveAllSettings(@RequestBody Map<String, String> settings) {

        settings.forEach((k, v) -> {
            ConfigurationSetting entity =
                    configurationSettingRepository
                            .findById(k)                       // ① 존재 여부 확인
                            .orElseGet(() ->                  // ② 없으면 새로 생성
                                    new ConfigurationSetting(k, null));

            entity.setSettingValue(v);                    // ③ 값만 갱신
            configurationSettingRepository.save(entity);  //   INSERT 또는 UPDATE
        });

        return ResponseEntity.ok(
                Map.of("message", "설정이 저장되었습니다.")
        );
    }
}