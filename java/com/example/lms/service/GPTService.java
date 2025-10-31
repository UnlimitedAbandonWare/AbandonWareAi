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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;




@Service
public class GPTService {

    // ✨ 1. 로거(Logger) 추가
    private static final Logger log = LoggerFactory.getLogger(GPTService.class);

    private final WebClient openaiWebClient;
    private final CurrentModelRepository currentRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api.url}")
    private String apiUrl;

    // Resolve the API key from configuration or environment.  Prefer the
    // `openai.api.key` property and fall back to OPENAI_API_KEY.  Do not
    // include other vendor keys (e.g. GROQ_API_KEY) to avoid authentication
    // failures.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${openai.api.model:gpt-3.5-turbo}") // o3에서 gpt-3.5-turbo로 변경
    private String defaultModelFromProps;

    // 고급 모델을 위한 구성. openai.chat.model-high-tier 가 지정되지 않으면 기본 모델을 재사용한다.
    @Value("${openai.chat.model-high-tier:${openai.api.model:${openai.chat.model:gpt-5-mini}}}")
    private String highTierModel;
    // true인 경우 항상 상위 티어 모델을 사용한다.
    @Value("${openai.chat.force-high-tier:false}")
    private boolean forceHighTier;

    public GPTService(@Qualifier("openaiWebClient") WebClient openaiWebClient,
                      CurrentModelRepository currentRepo) {
        this.openaiWebClient = openaiWebClient;
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
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);

        // Invoke the OpenAI chat completions endpoint using WebClient
        String responseBody = openaiWebClient.post()
                .uri(apiUrl + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("OpenAI API empty body");
        }

        // ✨ 3. 응답에서 모델 정보를 "추출"하고 로그 남기기
        JsonNode root = objectMapper.readTree(responseBody);

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
            return overrideModel;
        }
        // forceHighTier가 true면 항상 고급 모델을 선택한다.
        if (forceHighTier) {
            return highTierModel;
        }
        return currentRepo.findById(1L)
                .map(CurrentModel::getModelId)
                .orElse(defaultModelFromProps);
    }
}