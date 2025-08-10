package com.example.lms.service.correction;

import com.example.lms.util.ProductAliasNormalizer;
// import dev.langchain4j.model.chat.ChatLanguageModel; // ⛔ 의존성 잡히기 전까지 비활성
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

    // private final ObjectProvider<ChatLanguageModel> chatModelProvider; // ⛔ 임시 비활성
    private final ObjectProvider<OpenAiService> openAiProvider;
    private final DomainTermDictionary dictionaryProvider;

    @Value("${query.correction.enabled:true}") private boolean enabled;
    @Value("${query.correction.model:gpt-4o-mini}") private String openAiModel;
    @Value("${query.correction.max-length:140}") private int maxLength;

    @Override
    public String correct(@Nullable String originalInput) {
        if (!enabled || originalInput == null || originalInput.isBlank() || originalInput.length() > maxLength) {
            return originalInput;
        }
        String aliased = ProductAliasNormalizer.normalize(originalInput);
        if (!aliased.equals(originalInput)) return aliased;

        Set<String> protectedTerms = extractProtectedTerms(originalInput);
        String protectionInstruction = protectedTerms.isEmpty() ? "" :
                String.format("- 다음 '보호어'는 원문 그대로 유지해줘: [%s]%n", String.join(", ", protectedTerms));

        String systemPrompt = """
                너는 한국어 검색어를 교정하는 똑똑한 AI야.
                - 맞춤법, 띄어쓰기, 명백한 오타만 자연스럽게 수정해.
                - 절대 단어의 의미를 바꾸거나 창의적으로 내용을 추가하지 마.
                %s- 추가 설명이나 따옴표 없이, 교정된 검색어만 한 줄로 출력해.
                """.formatted(protectionInstruction);

        try {
            OpenAiService openAi = openAiProvider.getIfAvailable();
            if (openAi == null) return originalInput;

            String corrected = callOpenAiJava(openAi, openAiModel, systemPrompt, originalInput);
            if (corrected == null || corrected.isBlank()) return originalInput;

            corrected = corrected.trim();
            if (!protectedTerms.isEmpty()) {
                String outLower = corrected.toLowerCase();
                boolean dropped = protectedTerms.stream().anyMatch(t -> !outLower.contains(t.toLowerCase()));
                if (dropped) return originalInput;
            }
            return corrected;
        } catch (Exception e) {
            log.debug("[QC] correction failed → passthrough: {}", e.toString());
            return originalInput;
        }
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
