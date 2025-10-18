package com.example.lms.client;

import com.example.lms.client.OpenAiModelDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import java.util.List;
import java.util.Collections;



/**
 * OpenAI API 클라이언트 (간단한 RestTemplate 사용 예시)
 */
@Component
public class OpenAiClient {

    private final WebClient openaiWebClient;
    private final String apiKey;
    private final String baseUrl;

    public OpenAiClient(
            @Qualifier("openaiWebClient") WebClient openaiWebClient,
            // Resolve the API key from the `openai.api.key` property or the
            // OPENAI_API_KEY environment variable when unset.  We do not
            // automatically fall back to other vendor keys (e.g. Groq) to
            // avoid invalid authentication.  Prefix validation is performed
            // when the key is first used.
            @Value("${openai.api.key:${OPENAI_API_KEY:}}") String apiKey,
            // Support both the `openai.api.base-url` and `openai.base-url`
            // properties for backwards compatibility.  The latter takes
            // precedence when defined.  Default to the official OpenAI URL.
            @Value("${openai.api.base-url:${openai.base-url:https://api.openai.com}}") String baseUrl
    ) {
        this.openaiWebClient = openaiWebClient;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * GET /v1/models 호출하여 모델 목록을 가져옵니다.
     */
    public List<OpenAiModelDto> listModels() {
        // Prevent calls with a non-OpenAI key.  Keys should start with
        // "sk-".  If the environment is misconfigured (e.g. a Groq
        // "gsk-" key is assigned to OPENAI_API_KEY) we fail fast.
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-")) {
            throw new IllegalStateException(
                "OPENAI_API_KEY invalid (must start with 'sk-'). " +
                "Did you paste Groq 'gsk_' or Brave key?"
            );
        }
        String url = baseUrl + "/v1/models";
        try {
            String body = openaiWebClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (body == null || body.isBlank()) {
                return Collections.emptyList();
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            java.util.List<OpenAiModelDto> out = new java.util.ArrayList<>();
            for (JsonNode n : data) {
                OpenAiModelDto dto = new OpenAiModelDto();
                dto.setId(n.path("id").asText(""));
                dto.setOwnedBy(n.path("owned_by").asText(""));
                dto.setOwnedBy(n.path("owned_by").asText(""));
                out.add(dto);
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // 내부 DTO: /v1/models 응답 래핑
    private static class ModelsResponse {
        private List<OpenAiModelDto> data;

        public List<OpenAiModelDto> getData() {
            return data;
        }

        public void setData(List<OpenAiModelDto> data) {
            this.data = data;
        }
    }
}