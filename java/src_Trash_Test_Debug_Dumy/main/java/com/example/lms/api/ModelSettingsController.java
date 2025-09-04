package com.example.lms.api;

import com.example.lms.service.ModelSettingsService;
import com.example.lms.config.ModelGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/settings") // API 경로를 /api/settings 로 통일하여 관리
@RequiredArgsConstructor
public class ModelSettingsController {

    // 컨트롤러는 이제 Repository를 직접 다루지 않고, 비즈니스 로직을 가진 Service만 호출합니다.
    private final ModelSettingsService modelSettingsService;

    /**
     * Guard that enforces allowed model identifiers.  When a request
     * attempts to set a model outside the permitted list this guard
     * returns a 400 response immediately rather than propagating the
     * request to the service layer.
     */
    private final ModelGuard modelGuard;
    private final org.springframework.core.env.Environment env;

    /**
     * 기본 채팅 모델을 변경하고 저장합니다.
     * 이제 모델을 저장하기 전에 반드시 유효성을 검사합니다.
     * @param payload 요청 본문, 예: {"model": "gpt-4"}
     * @return 성공 또는 실패 메시지를 담은 JSON 응답
     */
    @PostMapping("/model") // 엔드포인트를 /model 로 명확히 함
    public ResponseEntity<?> saveDefaultModel(@RequestBody Map<String, String> payload) {
        String requested = payload != null ? payload.get("model") : null;
        if (requested == null || requested.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "모델 ID가 비어있습니다."));
        }
        // Determine the active provider from configuration; default to OpenAI.
        String provider = env.getProperty("llm.provider", "openai");
        // Compute the applied model via the provider-aware guard.
        String applied = modelGuard.requireAllowedOrFallback(requested, provider);
        boolean fallback = !requested.equals(applied);
        log.info("[ModelSettings] 기본 모델 저장 요청: {} (applied={}, provider={})", requested, applied, provider);
        try {
            // Persist the applied model via the service.
            modelSettingsService.changeCurrentModel(applied);
            return ResponseEntity.ok(
                    Map.of(
                            "applied", applied,
                            "fallback", fallback,
                            "allowed", modelGuard.allowedFor(provider)
                    )
            );
        } catch (IllegalArgumentException e) {
            log.warn("[ModelSettings] 모델 저장 실패: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    Map.of("error", "invalid_model", "message", e.getMessage())
            );
        } catch (Exception e) {
            log.error("[ModelSettings] 모델 저장 중 서버 오류 발생", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "모델 저장 중 서버 오류가 발생했습니다."));
        }
    }
}