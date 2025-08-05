// src/main/java/com/example/lms/service/ModelFetchService.java
package com.example.lms.service;

import com.example.lms.entity.ModelEntity;
import com.example.lms.model.ModelInfo;
import com.example.lms.repository.ModelEntityRepository;
import com.example.lms.repository.ModelInfoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OpenAI /v1/models 목록을 주기적으로 동기화하여
 *   1) 상세 메타(ModelInfo)
 *   2) 드롭다운용 요약(ModelEntity)
 * 두 테이블을 모두 **업서트(upsert) 방식**으로 갱신한다.
 */
@EnableScheduling
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFetchService {

    /** application.yml 의 openai.api.key 값을 주입 */
    @Value("${openai.api.key}")
    private String openaiApiKey;

    private final ModelInfoRepository   modelInfoRepo;
    private final ModelEntityRepository modelEntityRepo;

    private final RestTemplate rest = new RestTemplate();

    /**
     * 애플리케이션 기동 직후 1회 + 매시 정각마다 실행
     */
    @PostConstruct
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void updateModelList() {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            log.error("[ModelFetch] OpenAI API Key 미설정 — application.yml 의 'openai.api.key' 확인 필요");
            return;
        }

        try {
            /* ----------------------------------------------------------------
             * 1) OpenAI /v1/models 호출
             * ---------------------------------------------------------------- */
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(openaiApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<JsonNode> res = rest.exchange(
                    "https://api.openai.com/v1/models",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    JsonNode.class);

            JsonNode data = res.getBody().path("data");
            if (!data.isArray()) {
                log.warn("[ModelFetch] 예상치 못한 응답 형식: {}", data);
                return;
            }

            /* ----------------------------------------------------------------
             * 2) 기존 레코드를 한 번에 가져와 Map 으로 보관
             *    → select 난사 방지 & JPA saveAll() 한 방에!
             * ---------------------------------------------------------------- */
            Map<String, ModelInfo>   existingInfoMap = modelInfoRepo.findAll().stream()
                    .collect(Collectors.toMap(ModelInfo::getModelId, Function.identity()));

            Map<String, ModelEntity> existingEntityMap = modelEntityRepo.findAll().stream()
                    .collect(Collectors.toMap(ModelEntity::getModelId, Function.identity()));

            List<ModelInfo>   infosToSave    = new ArrayList<>();
            List<ModelEntity> entitiesToSave = new ArrayList<>();

            /* ----------------------------------------------------------------
             * 3) JSON → Entity 변환 (owner 기본값 처리) + 리스트 수집
             * ---------------------------------------------------------------- */
            for (JsonNode m : data) {
                String id       = m.path("id").asText();
                String objType  = m.path("object").asText();
                long   created  = m.path("created").asLong();
                String ownedBy  = m.path("owned_by").asText(null);

                // owner 필드는 NOT NULL 제약 — 비어 있으면 기본값 openai
                if (ownedBy == null || ownedBy.isBlank()) {
                    ownedBy = "openai";
                }

                /* -------- ModelInfo (상세) -------- */
                ModelInfo info = existingInfoMap.getOrDefault(id, new ModelInfo());
                info.setModelId(id);
                info.setObjectType(objType);
                info.setCreated(created);
                info.setOwnedBy(ownedBy);
                infosToSave.add(info);

                /* -------- ModelEntity (드롭다운용 요약) -------- */
                ModelEntity entity = existingEntityMap.getOrDefault(id, new ModelEntity());
                entity.setModelId(id);
                entitiesToSave.add(entity);
            }

            /* ----------------------------------------------------------------
             * 4) 한 방에 업서트
             * ---------------------------------------------------------------- */
            modelInfoRepo.saveAll(infosToSave);
            modelEntityRepo.saveAll(entitiesToSave);

            log.info("[ModelFetch] 동기화 완료 — {}개 모델 저장/업데이트", entitiesToSave.size());

        } catch (Exception ex) {
            // 트랜잭션이 rollback 되며 예외가 전파됨
            log.error("[ModelFetch] 모델 목록 동기화 실패", ex);
        }
    }

    /** 컨트롤러에서 호출: 저장된 모델 전부 반환 */
    public List<ModelEntity> getAllModels() {
        return modelEntityRepo.findAll();
    }
}
