package com.example.lms.api;

import com.example.lms.service.ModelSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@RestController
@RequestMapping("/api/settings") // API 경로를 /api/settings 로 통일하여 관리
@RequiredArgsConstructor
public class ModelSettingsController {
    private static final Logger log = LoggerFactory.getLogger(ModelSettingsController.class);

    // 컨트롤러는 이제 Repository를 직접 다루지 않고, 비즈니스 로직을 가진 Service만 호출합니다.
    private final ModelSettingsService modelSettingsService;

    /**
     * 기본 채팅 모델을 변경하고 저장합니다.
     * 이제 모델을 저장하기 전에 반드시 유효성을 검사합니다.
     * @param payload 요청 본문, 예: {"model": "gpt-4"}
     * @return 성공 또는 실패 메시지를 담은 JSON 응답
     */
    @PostMapping("/model") // 엔드포인트를 /model 로 명확히 함
    public ResponseEntity<?> saveDefaultModel(@RequestBody Map<String, String> payload) {
        String newModelId = payload.get("model");
        if (newModelId == null || newModelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "모델 ID가 비어있습니다."));
        }

        log.info("[ModelSettings] 기본 모델 저장 요청: {}", newModelId);
        try {
            // 1. ModelSettingsService를 통해 '검증'과 '저장' 로직을 위임
            modelSettingsService.changeCurrentModel(newModelId);

            // 2. 성공 시, 클라이언트가 확인하기 좋은 명확한 메시지를 전달
            return ResponseEntity.ok(Map.of("message", "기본 모델이 '" + newModelId + "'(으)로 저장되었습니다."));

        } catch (IllegalArgumentException e) {
            // 3. 서비스에서 '존재하지 않는 모델' 예외가 발생하면, 400 Bad Request로 클라이언트에 알림
            log.warn("[ModelSettings] 모델 저장 실패: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));

        } catch (Exception e) {
            // 4. 그 외의 예상치 못한 서버 오류 처리
            log.error("[ModelSettings] 모델 저장 중 서버 오류 발생", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "모델 저장 중 서버 오류가 발생했습니다."));
        }
    }
}