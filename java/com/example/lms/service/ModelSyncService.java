// src/main/java/com/example/lms/service/ModelSyncService.java
package com.example.lms.service;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

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
/ ... 필요한 필드 업데이트 계속 ...

            fetchedIds.add(modelId);
            toSave.add(e);
        });

// 3) 삭제 대상 산출 및 일괄 삭제
        List<String> idsToDelete = existingMap.keySet().stream()
                .filter(id -> !fetchedIds.contains(id))
                .toList();
        if (!idsToDelete.isEmpty()) {
            modelRepo.deleteAllById(idsToDelete);
        }

// 4) 일괄 업서트
        if (!toSave.isEmpty()) {
            modelRepo.saveAll(toSave);
        }

        log.info("📦 Fetched={}, Upserted={}, Deleted={}", fetchedIds.size(), toSave.size(), idsToDelete.size());
    }
}
