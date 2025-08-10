package com.example.lms.service.correction;

import com.example.lms.util.ProductAliasNormalizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMQueryCorrectionService implements QueryCorrectionService {

    /** 우선 경로: LangChain4j ChatLanguageModel (존재 시) */
    private final ObjectProvider<ChatLanguageModel> chatModelProvider;

    /** 폴백 경로: theokanning OpenAiService */
    private final ObjectProvider<OpenAiService> openAiProvider;

    /** 보호어 사전 */
    private final DomainTermDictionary dictionaryProvider;

    @Value("${query.correction.enabled:true}")
    private boolean enabled;

    @Value("${query.correction.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${query.correction.max-length:140}")
    private int maxLength;

    @Override
    public String correct(@Nullable String originalInput) {
        if (!enabled) return originalInput;
        if (originalInput == null || originalInput.isBlank()) return originalInput;
        if (originalInput.length() > maxLength) return originalInput;

        // 1) 별칭 정규화
        String aliased = ProductAliasNormalizer.normalize(originalInput);
        if (!aliased.equals(originalInput)) {
            log.debug("[QC] Alias matched. Bypass LLM: '{}' -> '{}'", originalInput, aliased);
            return aliased;
        }

        // 2) 보호어 추출 + 프롬프트 가드
        Set<String> protectedTerms = extractProtectedTerms(originalInput);
        String protectionInstruction = protectedTerms.isEmpty()
                ? ""
                : String.format("- 다음 '보호어'는 원문 그대로 유지해줘: [%s]%n",
                String.join(", ", protectedTerms));

        String systemPrompt = """
                너는 한국어 검색어를 교정하는 똑똑한 AI야.
                - 맞춤법, 띄어쓰기, 명백한 오타만 자연스럽게 수정해.
                - 절대 단어의 의미를 바꾸거나 창의적으로 내용을 추가하지 마.
                %s- 추가 설명이나 따옴표 없이, 교정된 검색어만 한 줄로 출력해.
                """.formatted(protectionInstruction);

        String corrected;
        try {
            ChatLanguageModel chatModel = chatModelProvider.getIfAvailable();
            if (chatModel != null) {
                // LangChain4j 1.0.x 권장 API: chat(...)
                corrected = chatModel.chat(systemPrompt + "\n\n" + originalInput); // generate(...)도 동작은 함. :contentReference[oaicite:4]{index=4}
            } else {
                OpenAiService openAi = openAiProvider.getIfAvailable();
                if (openAi == null) {
                    log.debug("[QC] No LLM available. Passthrough.");
                    return originalInput;
                }
                corrected = callOpenAiJava(openAi, openAiModel, systemPrompt, originalInput);
            }
        } catch (Exception e) {
            log.debug("[QC] Correction failed → passthrough: {}", e.toString());
            return originalInput; // fail-open
        }

        if (corrected == null || corrected.isBlank()) return originalInput;
        corrected = corrected.trim();

        // 3) 사후 검증: 보호어 누락 방지
        if (!protectedTerms.isEmpty()) {
            String outLower = corrected.toLowerCase();
            boolean dropped = protectedTerms.stream().anyMatch(t -> !outLower.contains(t.toLowerCase()));
            if (dropped) {
                log.warn("[QC] Over-correction detected. Rollback. orig='{}', corrected='{}'", originalInput, corrected);
                return originalInput;
            }
        }
        log.debug("[QC] Correction applied: '{}' -> '{}'", originalInput, corrected);
        return corrected;
    }

    private Set<String> extractProtectedTerms(String text) {
        Set<String> found = dictionaryProvider.findKnownTerms(text);
        return (found == null) ? Set.of() : found;
    }

    private static String callOpenAiJava(OpenAiService openAi, String model, String sys, String user) {
        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(
                        new ChatMessage(ChatMessageRole.SYSTEM.value(), sys),
                        new ChatMessage(ChatMessageRole.USER.value(), user)
                ))
                .temperature(0d)
                .topP(0.05d)
                .build();

        String out = openAi.createChatCompletion(req)
                .getChoices().get(0).getMessage().getContent();
        return out == null ? "" : out.trim();
    }
}
