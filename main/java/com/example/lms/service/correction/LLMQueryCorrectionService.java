package com.example.lms.service.correction;

import com.example.lms.util.ProductAliasNormalizer;
import com.example.lms.trace.SafeRedactor;
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
    @Value("${query.correction.model:${llm.chat-model:gemma4:26b}}")
    private String openAiModel;
    @Value("${query.correction.max-length:140}")
    private int maxLength;

    // --- @RequiredArgsConstructor ????앹꽦??吏곸젒 ?묒꽦 ---
    public LLMQueryCorrectionService(
            ObjectProvider<ChatModel> chatModelProvider,
            @Qualifier("defaultDomainTermDictionary") DomainTermDictionary dictionaryProvider, // ???뱀젙 鍮??좏깮
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
                String.format("- ?ㅼ쓬 '蹂댄샇?????먮Ц 洹몃?濡??좎??댁쨾: [%s]%n", String.join(", ", protectedTerms));

        String systemPrompt = """
                ?덈뒗 ?쒓뎅??寃?됱뼱瑜?援먯젙?섎뒗 ?묐삊??AI??
                - 留욎땄踰? ?꾩뼱?곌린, 紐낅갚???ㅽ?留??먯뿰?ㅻ읇寃??섏젙??
                - ?덈? ?⑥뼱???섎?瑜?諛붽씀嫄곕굹 李쎌쓽?곸쑝濡??댁슜??異붽??섏? 留?
                %s- 異붽? ?ㅻ챸?대굹 ?곗샂???놁씠, 援먯젙??寃?됱뼱留???以꾨줈 異쒕젰??
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
            log.debug("[QC] correction failed passthrough. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
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
    private String callChatModel(ChatModel llm, String queryCorrectionPrompt) {
        if (llm == null || queryCorrectionPrompt == null) return "";
        try {
            var res = llm.chat(UserMessage.from(queryCorrectionPrompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text().trim();
        } catch (Exception e) {
            log.debug("[LLMQueryCorrection] ChatModel call failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return "";
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
