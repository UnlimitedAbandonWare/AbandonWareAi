// src/main/java/com/example/lms/service/ModelSyncService.java
package com.example.lms.service;

import com.example.lms.entity.ModelEntity;
import com.example.lms.repository.ModelEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSyncService {

    private final ModelEntityRepository modelRepo;
    private final RestTemplate          restTemplate = new RestTemplate();
    private final ObjectMapper          objectMapper = new ObjectMapper();

    @Value("${openai.api.url:https://api.openai.com/v1}")
    private String apiUrl;

    @Value("${openai.api.key}")
    private String apiKey;

    @PostConstruct
    public void init() {
        fetchAndStoreModels();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void fetchAndStoreModels() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("🔑 OpenAI API Key 미설정 — application.yml 의 openai.api.key 확인!");
            return;
        }

        String url = apiUrl + "/models";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> req = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                log.warn("Failed to fetch models: HTTP {}", resp.getStatusCode());
                return;
            }

            JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
            if (!data.isArray()) {
                log.warn("Unexpected response format: {}", data);
                return;
            }
// --- 기존 deleteAll() 로직 제거 ---

// 1. 기존 데이터를 한 번에 조회하여 Map으로 변환
            List<ModelEntity> existing = modelRepo.findAll();
            Map<String, ModelEntity> existingMap = existing.stream()
                    .collect(Collectors.toMap(ModelEntity::getModelId, e -> e));

// 2. API 결과 기반으로 저장할 엔티티 목록과 최신 ID 집합 생성
            List<ModelEntity> toSave = new ArrayList<>();
            Set<String> fetchedIds = new HashSet<>();

            data.forEach(node -> { // 'data'는 API 응답 JSON을 파싱한 결과입니다.
                String modelId = node.path("id").asText();
                fetchedIds.add(modelId);

                // Map에서 기존 엔티티를 찾거나, 없으면 새로 생성
                ModelEntity e = existingMap.getOrDefault(modelId, new ModelEntity());

                // ... 엔티티 필드 업데이트 로직 ...
                e.setModelId(modelId);
                // ...

                toSave.add(e);
            });

// 3. 더 이상 존재하지 않는 모델들을 한 번에 삭제
            List<String> idsToDelete = existingMap.keySet().stream()
                    .filter(id -> !fetchedIds.contains(id))
                    .toList();

            if (!idsToDelete.isEmpty()) {
                modelRepo.deleteAllById(idsToDelete);
            }

// 4. 저장 및 업데이트를 한 번에 처리
            modelRepo.saveAll(toSave);

            log.info("📦 Fetched={}, Upserted={}, Deleted={}", fetchedIds.size(), toSave.size(), idsToDelete.size());
            List<ModelEntity> toSave = new ArrayList<>();
            data.forEach(node -> {
                String modelId   = node.path("id").asText();
                long   createdTs = node.path("created").asLong(0);

                ModelEntity e = modelRepo.findById(modelId)
                        .orElseGet(ModelEntity::new);
                e.setModelId(modelId);
                e.setReleaseDate(
                        Instant.ofEpochSecond(createdTs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                );

                // ← owned_by 설정: 값이 없으면 "openai" 기본값
                String owner = node.path("owned_by").asText("openai");
                e.setOwner(owner);

                // ...필요시 ctxWindow, features 등 추가 필드 세팅...
                toSave.add(e);
            });

            modelRepo.saveAll(toSave);
            log.info("📦 Fetched & saved {} models", toSave.size());

        } catch (Exception ex) {
            log.error("Error syncing OpenAI models", ex);
        }
    }
}
