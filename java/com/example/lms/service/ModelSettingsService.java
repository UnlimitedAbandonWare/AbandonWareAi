package com.example.lms.service;

import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import java.util.Locale;

import org.springframework.util.StringUtils; // ✨ [추가] StringUtils import

@Service
@RequiredArgsConstructor
public class ModelSettingsService {
    private static final Logger log = LoggerFactory.getLogger(ModelSettingsService.class);

    private final ModelEntityRepository  modelRepo;
    private final CurrentModelRepository currentRepo;
    @Value("${llm.openai.endpoint-compat.save-guard.mode:${nova.llm.endpoint-compat.save-guard.mode:BLOCK}}")
    private String endpointCompatSaveGuardMode;


    /**
     * UI에서 선택한 모델을 기본 모델로 저장합니다. (개선된 버전)
     * @param modelId 저장할 모델 ID
     */
    @Transactional
public void changeCurrentModel(String modelId) {
    // 1. [개선] StringUtils.hasText()로 입력값 검증을 더 깔끔하게 처리
    if (!StringUtils.hasText(modelId)) {
        throw new IllegalArgumentException("모델 ID가 비어 있습니다.");
    }

    // 1-1. 임베딩/레거시 전용 모델을 기본 채팅 모델로 사용하는 것 차단
    String lower = modelId.toLowerCase(Locale.ROOT);
    if (lower.equals("babbage-002") || lower.contains("embedding")) {
        log.warn("[ModelSettings] '{}' is embedding/legacy model; refusing to set as default chat model", modelId);
        throw new IllegalArgumentException("임베딩/레거시 전용 모델은 기본 채팅 모델로 사용할 수 없습니다.");
    }

        // 1-2. Model↔Endpoint 호환성 저장 가드 (chat/completions vs completions)
        if (OpenAiEndpointCompatibility.isLikelyCompletionsOnlyModelId(modelId)) {
            String msg = "모델 저장 오류 : \"" + modelId + "\" 은(는) /v1/chat/completions(채팅) 엔드포인트와 호환되지 않을 가능성이 높습니다. "
                    + "(/v1/completions 전용 모델로 추정) 채팅 모델로 변경하거나, llm.openai.endpoint-compat.save-guard.mode=WARN/ALLOW 또는 nova.llm.endpoint-compat.save-guard.mode=WARN/ALLOW 로 조정하세요.";
            String mode = (endpointCompatSaveGuardMode == null ? "BLOCK" : endpointCompatSaveGuardMode.trim());
            if ("WARN".equalsIgnoreCase(mode)) {
                log.warn("[ModelSettings] {} (mode=WARN)", msg);
            } else if (!"ALLOW".equalsIgnoreCase(mode)) {
                log.warn("[ModelSettings] {} (mode={})", msg, mode);
                throw new IllegalArgumentException(msg);
            }
        }


// 2. 저장 전, DB의 'models' 테이블에 해당 ID가 존재하는지 먼저 확인
        if (!modelRepo.existsById(modelId)) {
            log.warn("존재하지 않는 모델 ID로 변경 시도: {}", modelId);
            throw new IllegalArgumentException("선택한 모델 \"" + modelId + "\" 는 등록되지 않은 모델입니다.");
        }

        // 3. [개선] ifPresentOrElse를 사용하여 '있을 때'와 '없을 때'의 로직을 명확히 분리
        Optional<CurrentModel> optionalCurrentModel = currentRepo.findById(1L);

        optionalCurrentModel.ifPresentOrElse(
                // id=1 레코드가 이미 존재할 경우
                currentModel -> {
                    currentModel.setModelId(modelId.trim());
                    currentRepo.save(currentModel);
                },
                // id=1 레코드가 없을 경우
                () -> {
                    CurrentModel newCurrentModel = new CurrentModel();
                    newCurrentModel.setId(1L); // PK 고정
                    newCurrentModel.setModelId(modelId.trim());
                    currentRepo.save(newCurrentModel);
                }
        );

        log.info("✅ 기본 모델이 '{}'(으)로 성공적으로 변경되었습니다.", modelId);
    }
}