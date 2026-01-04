// src/main/java/com/example/lms/service/ModelSyncService.java
package com.example.lms.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import com.example.lms.entity.ModelEntity;
import com.example.lms.repository.ModelEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.Cacheable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Component
@RequiredArgsConstructor
public class ModelSyncService {
    private static final Logger log = LoggerFactory.getLogger(ModelSyncService.class);

    private final ModelEntityRepository modelRepo;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api.url:http://localhost:11434/v1}")
    private String apiUrl;

    // Resolve the API key from configuration or environment.  Prefer
    // `openai.api.key` and fall back to OPENAI_API_KEY only. Do not fall
    // back to other vendor keys (e.g. GROQ_API_KEY) to prevent mismatched
    // credentials.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void fetchAndStoreModels() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("🔑 OpenAI API Key 미설정 - application.yml 의 openai.api.key 확인!");
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

            // 1) 기존 전체 조회 → Map
            List<ModelEntity> existing = modelRepo.findAll();
            Map<String, ModelEntity> existingMap = existing.stream()
                    .collect(Collectors.toMap(ModelEntity::getModelId, e -> e));

            // 2) 업서트 대상 및 신규 ID 수집
            Set<String> fetchedIds = new HashSet<>();
            List<ModelEntity> newEntities = new ArrayList<>();

            for (JsonNode node : data) {
                String modelId = node.path("id").asText(null);
                if (modelId == null || modelId.isBlank()) {
                    continue;
                }

                fetchedIds.add(modelId);

                ModelEntity entity = existingMap.get(modelId);
                boolean isNew = (entity == null);

                if (isNew) {
                    entity = new ModelEntity();
                    entity.setModelId(modelId);
                    newEntities.add(entity);
                }

                // 공통 필드 업데이트 (기존/신규 모두)
                long createdTs = node.path("created").asLong(0);
                if (createdTs > 0) {
                    entity.setReleaseDate(
                            Instant.ofEpochSecond(createdTs)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                    );
                }

                try {
                    String owner = node.path("owned_by").asText(null);
                    if (owner != null && !owner.isBlank()) {
                        entity.setOwner(owner);
                    } else {
                        if (entity.getOwner() == null || entity.getOwner().isBlank()) {
                            entity.setOwner("openai");
                        }
                    }
                } catch (Exception ignore) {
                    if (entity.getOwner() == null || entity.getOwner().isBlank()) {
                        entity.setOwner("openai");
                    }
                }
            }    

            // 3) 삭제 대상(ID 차집합) 계산 → 일괄 삭제
            List<String> idsToDelete = existingMap.keySet().stream()
                    .filter(id -> !fetchedIds.contains(id))
                    .toList();
            if (!idsToDelete.isEmpty()) {
                modelRepo.deleteAllById(idsToDelete);
            }

            // 4) 신규만 저장 (기존 엔티티는 영속성 컨텍스트에서 dirty checking)
            if (!newEntities.isEmpty()) {
                modelRepo.saveAll(newEntities);
            }

            int updatedCount = fetchedIds.size() - newEntities.size();
            log.info("📦 Fetched={}, New={}, Updated(dirty-check)={}, Deleted={}",
                    fetchedIds.size(), newEntities.size(), updatedCount, idsToDelete.size());

        } catch (Exception e) {
            log.error("Model sync 실패", e);
        }
    }

    @Cacheable(value = "models", key = "#modelId", unless = "#result == null")
    public ModelEntity getCachedModel(String modelId) {
        return modelRepo.findById(modelId).orElse(null);
    }

}