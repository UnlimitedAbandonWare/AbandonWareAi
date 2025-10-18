package com.example.lms.service.correction;

import com.example.lms.util.ProductAliasNormalizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Service
public class LLMQueryCorrectionService implements QueryCorrectionService {
    private static final Logger log = LoggerFactory.getLogger(LLMQueryCorrectionService.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final DomainTermDictionary dictionaryProvider;
    private final PromptBuilder promptBuilder;

    @Value("${query.correction.enabled:true}")
    private boolean enabled;
    @Value("${query.correction.model:gpt-5-mini}")
    private String openAiModel;
    @Value("${query.correction.max-length:140}")
    private int maxLength;

    // --- @RequiredArgsConstructor 대신 생성자 직접 작성 ---
    public LLMQueryCorrectionService(
            ObjectProvider<ChatModel> chatModelProvider,
            @Qualifier("defaultDomainTermDictionary") DomainTermDictionary dictionaryProvider, // ✅ 특정 빈 선택
            PromptBuilder promptBuilder
    ) {
        this.chatModelProvider = chatModelProvider;
        this.dictionaryProvider = dictionaryProvider;
        this.promptBuilder = promptBuilder;
    }
    // ----------------------------------------------------

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
            ChatModel llm = chatModelProvider.getIfAvailable();
            if (llm == null) return originalInput;
            String corrected = callChatModel(llm, promptBuilder.build(
                PromptContext.builder()
                    .systemInstruction(systemPrompt)
                    .userQuery(originalInput)
                    .domain("query-correction")
                    .subject("spellfix")
                    .build()
            ));
            if (corrected == null || corrected.isBlank()) return originalInput;
            corrected = corrected.trim();
            if (!protectedTerms.isEmpty()) {
                String outLower = corrected.toLowerCase(Locale.ROOT);
                boolean dropped = protectedTerms.stream()
                        .anyMatch(t -> !outLower.contains(t.toLowerCase(Locale.ROOT)));
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

    /**
     * Execute a correction prompt via the provided ChatModel.  The prompt
     * should include any system instructions and user content concatenated
     * together.  Returns the trimmed response text or an empty string on
     * failure.
     */
    private String callChatModel(ChatModel llm, String prompt) {
        if (llm == null || prompt == null) return "";
        try {
            var res = llm.chat(UserMessage.from(prompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text().trim();
        } catch (Exception e) {
            log.debug("[LLMQueryCorrection] ChatModel call failed: {}", e.toString());
            return "";
        }
    }
}