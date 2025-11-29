package com.example.lms.service;

import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


import org.springframework.util.StringUtils; // ✨ [추가] StringUtils import

@Service
@RequiredArgsConstructor
public class ModelSettingsService {
    private static final Logger log = LoggerFactory.getLogger(ModelSettingsService.class);

    private final ModelEntityRepository  modelRepo;
    private final CurrentModelRepository currentRepo;

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