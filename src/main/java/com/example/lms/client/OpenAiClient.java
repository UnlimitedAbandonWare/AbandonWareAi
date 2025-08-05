package com.example.lms.client;

import com.example.lms.client.OpenAiModelDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.List;
import java.util.Collections;

/**
 * OpenAI API 클라이언트 (간단한 RestTemplate 사용 예시)
 */
@Component
public class OpenAiClient {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String baseUrl;

    public OpenAiClient(
            RestTemplate restTemplate,
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.api.base-url:https://api.openai.com}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * GET /v1/models 호출하여 모델 목록을 가져옵니다.
     */
    public List<OpenAiModelDto> listModels() {
        String url = baseUrl + "/v1/models";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            ResponseEntity<ModelsResponse> resp = restTemplate.exchange(
                    url, HttpMethod.GET, request, ModelsResponse.class
            );
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return resp.getBody().getData();
            }
        } catch (Exception e) {
            // TODO: 로깅 및 에러 처리
            e.printStackTrace();
        }
        return Collections.emptyList();
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
