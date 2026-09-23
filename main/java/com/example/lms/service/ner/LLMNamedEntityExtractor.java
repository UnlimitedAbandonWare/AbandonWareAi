package com.example.lms.service.ner;

import com.example.lms.service.correction.DomainTermDictionary;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * LLM怨??꾨찓???ъ쟾???④퍡 ?ъ슜?섏뿬 ?띿뒪?몄뿉??紐낅챸 媛쒖껜(Named Entity)瑜?異붿텧?⑸땲??
 */
@Component
@RequiredArgsConstructor
public class LLMNamedEntityExtractor implements NamedEntityExtractor {
    private static final Logger log = LoggerFactory.getLogger(LLMNamedEntityExtractor.class);

    /**
     * The chat model used to perform named entity extraction.  We avoid
     * depending on the OpenAI-Java SDK directly so that the LLM pipeline can
     * be unified on LangChain4j.  A bean of type {@link ChatModel} must be
     * available in the Spring context for this extractor to operate.  When no
     * chat model is available, the extractor falls back to returning only
     * dictionary terms.
     */
    private final ChatModel chatModel;
    private final DomainTermDictionary dict;
    private final PromptBuilder promptBuilder;

    /**
     * Model name for the underlying LLM.  Defaults to the latest GPT-5 mini
     * model.  This property can be overridden via application configuration.
     */
    @Value("${ner.model:${llm.chat-model:gemma4:26b}}")
    private String model;

    @Value("${ner.llm.enabled:${NER_LLM_ENABLED:true}}")
    private boolean llmEnabled = true;

    /**
     * 二쇱뼱吏??띿뒪?몄뿉??紐낅챸 媛쒖껜 紐⑸줉??異붿텧?⑸땲??
     * @param text 紐낅챸 媛쒖껜瑜?異붿텧???먮낯 ?띿뒪??     * @return 異붿텧 諛??뺤젣??紐낅챸 媛쒖껜 臾몄옄??紐⑸줉
     */
    @Override
    public List<String> extract(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // 1. LLM???몄텧?섏뿬 ?띿뒪?몄뿉??紐낅챸 媛쒖껜 異붿텧
        String sysPrompt = """
                You extract named entities (people, items, places, game characters, organizations) from Korean text.
                Output ONLY a comma-separated list of short entity names. No explanation.
                Example: ?몃━?? ?먯떊, ?고???                """;
        String userPrompt = new StringBuilder(6 + text.length())
                .append("TEXT:\n")
                .append(text)
                .toString();

        String rawLlmOutput = "";
        try {
            if (llmEnabled && chatModel != null) {
                // Prompt composition rule: do not concatenate system+user prompts.
                // Use PromptBuilder.build(ctx) only.
                PromptContext ctx = PromptContext.builder()
                        .systemInstruction(sysPrompt)
                        .userQuery(userPrompt)
                        .build();
                String namedEntityExtractionPrompt = promptBuilder.build(ctx);
                var res = chatModel.chat(UserMessage.from(namedEntityExtractionPrompt));
                if (res != null && res.aiMessage() != null && res.aiMessage().text() != null) {
                    rawLlmOutput = res.aiMessage().text();
                }
            }
        } catch (Exception e) {
            log.debug("[NER] LLM extraction failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }

        // 2. LLM ?묐떟 ?뚯떛 諛?湲곕낯 ?뺤젣
        Set<String> extractedEntities = new LinkedHashSet<>(); // ?쒖꽌 蹂댁옣 諛?以묐났 ?쒓굅
        if (StringUtils.hasText(rawLlmOutput)) {
            // 肄ㅻ쭏(,)瑜?湲곗??쇰줈 遺꾨━?섍퀬 ?묒そ 怨듬갚 ?쒓굅
            for (String token : rawLlmOutput.split("\\s*,\\s*")) {
                String sanitizedToken = token.trim();
                // ?덈Т 吏㏃? ?좏겙? 臾댁떆
                if (sanitizedToken.length() >= 2) {
                    extractedEntities.add(sanitizedToken);
                }
            }
        }

        // 3. ?꾨찓???ъ쟾?먯꽌 蹂댄샇 ?⑹뼱(known terms)瑜?李얠븘 寃곌낵??諛섎뱶???ы븿
        try {
            extractedEntities.addAll(dict.findKnownTerms(text));
        } catch (Exception e) {
            log.warn("[NER] dictionary lookup failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            // ???④퀎???ㅽ뙣?섎뜑?쇰룄 ?꾩껜 ?꾨줈?몄뒪??怨꾩냽 吏꾪뻾
        }

        // 4. 理쒖쥌 寃곌낵??湲몄씠 諛?媛쒖닔 ?쒗븳
        return extractedEntities.stream()
                .map(s -> s.length() > 40 ? s.substring(0, 40) : s) // ?덈Т 湲?媛쒖껜紐??먮Ⅴ湲?                .limit(20) // 理쒕? 媛쒖닔 ?쒗븳
                .collect(Collectors.toList());
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
