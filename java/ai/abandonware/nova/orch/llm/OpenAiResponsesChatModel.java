package ai.abandonware.nova.orch.llm;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

/**
 * Adapter ChatModel that calls OpenAI Responses API (/v1/responses).
 *
 * <p>Used as a fail-soft route when the requested model is not compatible with /v1/chat/completions.</p>
 */
public final class OpenAiResponsesChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesChatModel.class);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String modelName;
    private final long timeoutMs;

    public OpenAiResponsesChatModel(String baseUrl, String apiKey, String modelName, long timeoutMs) {
        this.modelName = (modelName == null) ? "" : modelName;
        this.timeoutMs = timeoutMs;

        this.mapper = new ObjectMapper();
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        String input = OpenAiEndpointCompatibility.toCompletionsPrompt(messages);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", modelName);
        req.put("input", input);

        try {
            String json = client.post()
                    .uri("/responses")
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1_000L, timeoutMs)))
                    .onErrorResume(e -> Mono.error(e))
                    .block();

            String out = extractAssistantText(json);
            if (!StringUtils.hasText(out)) {
                out = "(empty responses output)";
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(out))
                    .build();

        } catch (WebClientResponseException wcre) {
            log.warn("OpenAI /responses failed: status={} model={}", wcre.getRawStatusCode(), modelName);
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses", "ROUTE_RESPONSES")
                    + "httpStatus: " + wcre.getRawStatusCode() + "\n";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();

        } catch (Exception e) {
            log.warn("OpenAI /responses failed: model={} ex={}", modelName, e.toString());
            String msg = ModelGuardSupport.buildExpectedFailureMessage(modelName, "/v1/responses", "ROUTE_RESPONSES")
                    + "error: " + e.getClass().getSimpleName() + "\n";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(msg))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantText(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            Map<String, Object> m = mapper.readValue(json, MAP);

            // 1) Some variants return output_text directly
            Object ot = m.get("output_text");
            if (ot instanceof String s && StringUtils.hasText(s)) {
                return s;
            }

            // 2) Common shape: output[] -> {type:"message", content:[{type:"output_text", text:"..."}]}
            Object out = m.get("output");
            if (out instanceof List<?> outList) {
                StringBuilder sb = new StringBuilder();
                for (Object o : outList) {
                    if (!(o instanceof Map<?, ?> mm)) {
                        continue;
                    }
                    Object type = mm.get("type");
                    if (!("message".equals(type))) {
                        continue;
                    }
                    Object content = mm.get("content");
                    if (!(content instanceof List<?> contentList)) {
                        continue;
                    }
                    for (Object c : contentList) {
                        if (!(c instanceof Map<?, ?> cm)) {
                            continue;
                        }
                        Object cType = cm.get("type");
                        if (!("output_text".equals(cType))) {
                            continue;
                        }
                        Object text = cm.get("text");
                        if (text instanceof String ts && StringUtils.hasText(ts)) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(ts);
                        }
                    }
                }
                return sb.toString();
            }
        } catch (Exception ignore) {
            // fallthrough
        }
        return null;
    }

    @Override
    public String toString() {
        return "OpenAiResponsesChatModel(" + modelName + ")";
    }
}
