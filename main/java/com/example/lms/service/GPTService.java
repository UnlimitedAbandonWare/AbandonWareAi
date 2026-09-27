package com.example.lms.service;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.OpenAiTokenParamCompat;

import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.trace.SafeRedactor;
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

    // ??1. 嚥≪뮄援?Logger) ?곕떽?
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

    @Value("${openai.api.model:${llm.chat-model:gemma4:26b}}")
    private String defaultModelFromProps;

    // ?⑥쥒??筌뤴뫀????袁る립 ?닌딄쉐. openai.chat.model-high-tier 揶쎛 筌왖?類ｋ┷筌왖 ??놁몵筌?疫꿸퀡??筌뤴뫀?????沅??븍립??
    @Value("${openai.chat.model-high-tier:${openai.api.model:${openai.chat.model:${llm.chat-model:gemma4:26b}}}}")
    private String highTierModel;
    // true??野껋럩????湲??怨몄맄 ?怨쀫선 筌뤴뫀????????뺣뼄.
    @Value("${openai.chat.force-high-tier:false}")
    private boolean forceHighTier;

    @Value("${openai.api.temperature.default:${llm.chat.temperature:0.3}}")
    private double defaultTemperature;

    public GPTService(@Qualifier("openaiWebClient") WebClient openaiWebClient,
                      CurrentModelRepository currentRepo) {
        this.openaiWebClient = openaiWebClient;
        this.currentRepo = currentRepo;
    }

    public String chatCompletion(String prompt, String overrideModel) throws Exception {
        String modelToUse = pickModel(overrideModel);
        String normalizedApiKey = requireOpenAiApiKey();
        log.info("[GPTService] requesting completion modelHash={} modelLength={}",
                SafeRedactor.hashValue(modelToUse), lengthOf(modelToUse));

        // Model selection is logged above with hash-only diagnostics.

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", prompt
        );
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelToUse);
        body.put("messages", List.of(message));
        body.put("temperature", defaultTemperature);
        String tokenKey = OpenAiTokenParamCompat.tokenParamKey(modelToUse, apiUrl);
        if (tokenKey != null && !tokenKey.isBlank() && !"none".equalsIgnoreCase(tokenKey)) {
            body.put(tokenKey, 1024);
        }

        // Invoke the OpenAI chat completions endpoint using WebClient
        String responseBody;
        try {
            responseBody = openaiWebClient.post()
                .uri(apiUrl + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            traceSuppressed("openai.chatCompletion", wcre);
            if (OpenAiTokenParamCompat.isUnsupportedMaxTokens(wcre)) {
                OpenAiTokenParamCompat.replaceTokenParamForRetry(body, 1024);
                responseBody = openaiWebClient.post()
                .uri(apiUrl + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            } else {
                throw wcre;
            }
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("OpenAI API empty body");
        }

        JsonNode root = objectMapper.readTree(responseBody);

        String responseModel = root.path("model").asText("N/A");
        log.info("[GPTService] received response modelHash={} modelLength={}",
                SafeRedactor.hashValue(responseModel), lengthOf(responseModel));

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
        // forceHighTier揶쎛 true筌???湲??⑥쥒??筌뤴뫀????醫뤾문??뺣뼄.
        if (forceHighTier) {
            return highTierModel;
        }
        return currentRepo.findById(1L)
                .map(CurrentModel::getModelId)
                .orElse(defaultModelFromProps);
    }

    private String requireOpenAiApiKey() {
        if (ConfigValueGuards.isMissing(apiKey)) {
            throw new IllegalStateException("OpenAI API key is missing");
        }
        return apiKey.trim();
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        log.debug("[GPTService] suppressed stage={} errorType={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
}
