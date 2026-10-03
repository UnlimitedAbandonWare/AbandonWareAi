package com.example.lms.api;

import com.example.lms.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;




@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
// ✅ 클래스 이름을 파일명과 동일하게 SettingsController로 수정했습니다.
public class SettingsController {

    /**
     * 공개 허용 설정 키 — UI가 실제로 읽고 쓰는 값만 나열한다.
     * SYSTEM_PROMPT 원문, API 키/토큰, 자격증명류 및 알 수 없는 DB 키는
     * GET 응답에도, POST 저장에도 포함하지 않는다.
     */
    private static final Set<String> PUBLIC_SETTING_KEYS = SettingsExposurePolicy.publicKeys();

    private final SettingsService settingsService;

    /**
     * 공개 가능한 설정만 'configuration_settings' 기반(기본값 + DB override)으로 반환합니다.
     * 내부 키는 allowlist에 없으므로 절대 노출되지 않습니다.
     *
     * @return 공개 설정 Key-Value Map
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> getAllSettings() {
        Map<String, String> all = settingsService.getAllSettings();
        Map<String, String> settingMap = PUBLIC_SETTING_KEYS.stream()
                .filter(all::containsKey)
                .filter(k -> !SettingsExposurePolicy.isCredentialValue(all.get(k)))
                .collect(Collectors.toMap(k -> k, all::get));

        return ResponseEntity.ok(settingMap);
    }

    /**
     * 공개 allowlist 안의 설정만 저장합니다. allowlist 밖 키가 하나라도 있으면
     * 전체 요청을 거부해 내부/비밀 키의 우회 저장을 막습니다.
     *
     * @param settingsToSave 프론트엔드에서 받은 설정값 Map
     * @return 성공 메시지
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> saveAllSettings(@RequestBody Map<String, String> settings) {

        if (settings == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "settings body is required"));
        }

        if (SettingsExposurePolicy.hasForbiddenWrite(settings)) {
            return ResponseEntity.badRequest().body(Map.of("code", "SETTINGS_SECRET_VALUE_FORBIDDEN"));
        }

        Set<String> rejected = settings.keySet().stream()
                .filter(k -> !PUBLIC_SETTING_KEYS.contains(k))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!rejected.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "unsupported setting keys",
                    "rejected", String.join(",", rejected)));
        }

        SettingsService.InvalidNumericSetting invalid = SettingsService.numericValidationError(settings);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(Map.of("message", "invalid numeric setting",
                    "key", invalid.key(), "reason", invalid.reason()));
        }
        settingsService.saveAllSettings(settings);

        return ResponseEntity.ok(
                Map.of("message", "설정이 저장되었습니다.")
        );
    }
}
