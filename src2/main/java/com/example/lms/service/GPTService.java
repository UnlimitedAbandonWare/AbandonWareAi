package com.example.lms.service;

import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GPTService {

    // ✨ 1. 로거(Logger) 추가
    private static final Logger log = LoggerFactory.getLogger(GPTService.class);

    private final RestTemplate restTemplate;
    private final CurrentModelRepository currentRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api.url}")
    private String apiUrl;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model:gpt-3.5-turbo}") // o3에서 gpt-3.5-turbo로 변경
    private String defaultModelFromProps;

    public GPTService(RestTemplate restTemplate,
                      CurrentModelRepository currentRepo) {
        this.restTemplate = restTemplate;
        this.currentRepo = currentRepo;
    }

    public String chatCompletion(String prompt, String overrideModel) throws Exception {
        String modelToUse = pickModel(overrideModel);

        // ✨ 2. 어떤 모델을 "요청"하는지 로그 남기기
        log.info("[GPTService] Requesting completion from model: '{}'", modelToUse);

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", prompt
        );
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelToUse);
        body.put("messages", List.of(message));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl + "/chat/completions",
                request,
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("[GPTService] OpenAI API error: {} - {}", response.getStatusCode(), response.getBody());
            throw new IllegalStateException("OpenAI API error: " + response.getStatusCode());
        }

        // ✨ 3. 응답에서 모델 정보를 "추출"하고 로그 남기기
        JsonNode root = objectMapper.readTree(response.getBody());

        // 사용된 모델 정보 추출
        String responseModel = root.path("model").asText("N/A");
        log.info("[GPTService] Received response from model: '{}'", responseModel);

        // 메시지 내용 추출
        JsonNode contentNode = root
                .path("choices")
                .get(0)
                .path("message")
                .path("content");

        return contentNode.asText();
    }

    public String askChatbot(String prompt) throws Exception {
        return chatCompletion(prompt, null);
    }

    private String pickModel(String overrideModel) {
        if (overrideModel != null && !overrideModel.isBlank()) {
            // log.debug("Using override model: {}", overrideModel);
            return overrideModel;
        }
        return currentRepo.findById(1L)
                .map(CurrentModel::getModelId)
                .orElse(defaultModelFromProps);
    }
}