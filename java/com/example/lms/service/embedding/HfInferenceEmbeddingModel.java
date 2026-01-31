package com.example.lms.service.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;




@Component
@RequiredArgsConstructor
public class HfInferenceEmbeddingModel implements EmbeddingModel {

    private final WebClient webClient;

    @Value("${embeddings.provider:openai}")
    private String provider;
    @Value("${embeddings.hf.api-url:https://api-inference.huggingface.co}")
    private String apiUrl;
    @Value("${embeddings.hf.api-key:}")
    private String apiKey;
    @Value("${embeddings.hf.model:BAAI/bge-small-en-v1.5}")
    private String modelId;

    @Override
    public Response<Embedding> embed(String text) {
        if (!"hf".equalsIgnoreCase(provider)) {
            return Response.from(null, null); // 다른 모델 경로가 사용됨
        }
        return Response.from(Embedding.from(callHf(text)), null);
    }

    // 안전을 위해 TextSegment 오버로드도 구현
    public Response<Embedding> embed(TextSegment segment) {
        String txt = segment == null ? "" : segment.text();
        return embed(txt);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (!"hf".equalsIgnoreCase(provider)) {
            return Response.from(null, null);
        }
        List<Embedding> out = new ArrayList<>();
        if (segments != null) {
            for (TextSegment s : segments) {
                String txt = s == null ? "" : s.text();
                out.add(Embedding.from(callHf(txt)));
            }
        }
        return Response.from(out, null);
    }

    private float[] callHf(String text) {
        try {
            String url = apiUrl + "/pipeline/feature-extraction/" + modelId;
            String resp = webClient.post().uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(java.util.Map.of("inputs", text))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(resp);
            var arr  = node.isArray() && node.get(0).isArray() ? node.get(0) : node;
            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble();
            return vec;
        } catch (Exception e) {
            return new float[0];
        }
    }
}