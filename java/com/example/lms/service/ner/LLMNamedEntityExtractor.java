package com.example.lms.service.ner;

import com.example.lms.service.correction.DomainTermDictionary;
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
 * LLM과 도메인 사전을 함께 사용하여 텍스트에서 명명 개체(Named Entity)를 추출합니다.
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
    @Value("${ner.model:gemma3:27b}")
    private String model;

    /**
     * 주어진 텍스트에서 명명 개체 목록을 추출합니다.
     * @param text 명명 개체를 추출할 원본 텍스트
     * @return 추출 및 정제된 명명 개체 문자열 목록
     */
    @Override
    public List<String> extract(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // 1. LLM을 호출하여 텍스트에서 명명 개체 추출
        String sysPrompt = """
                You extract named entities (people, items, places, game characters, organizations) from Korean text.
                Output ONLY a comma-separated list of short entity names. No explanation.
                Example: 푸리나, 원신, 폰타인
                """;
        String userPrompt = new StringBuilder(6 + text.length())
                .append("TEXT:\n")
                .append(text)
                .toString();

        String rawLlmOutput = "";
        try {
            if (chatModel != null) {
                // Prompt composition rule: do not concatenate system+user prompts.
                // Use PromptBuilder.build(ctx) only.
                PromptContext ctx = PromptContext.builder()
                        .systemInstruction(sysPrompt)
                        .userQuery(userPrompt)
                        .build();
                String prompt = promptBuilder.build(ctx);
                var res = chatModel.chat(UserMessage.from(prompt));
                if (res != null && res.aiMessage() != null && res.aiMessage().text() != null) {
                    rawLlmOutput = res.aiMessage().text();
                }
            }
        } catch (Exception e) {
            log.debug("[NER] LLM API 호출에 실패했습니다: {}", e.toString());
        }

        // 2. LLM 응답 파싱 및 기본 정제
        Set<String> extractedEntities = new LinkedHashSet<>(); // 순서 보장 및 중복 제거
        if (StringUtils.hasText(rawLlmOutput)) {
            // 콤마(,)를 기준으로 분리하고 양쪽 공백 제거
            for (String token : rawLlmOutput.split("\\s*,\\s*")) {
                String sanitizedToken = token.trim();
                // 너무 짧은 토큰은 무시
                if (sanitizedToken.length() >= 2) {
                    extractedEntities.add(sanitizedToken);
                }
            }
        }

        // 3. 도메인 사전에서 보호 용어(known terms)를 찾아 결과에 반드시 포함
        try {
            extractedEntities.addAll(dict.findKnownTerms(text));
        } catch (Exception e) {
            log.warn("[NER] 도메인 사전에서 용어 검색 중 예외 발생", e);
            // 이 단계는 실패하더라도 전체 프로세스는 계속 진행
        }

        // 4. 최종 결과의 길이 및 개수 제한
        return extractedEntities.stream()
                .map(s -> s.length() > 40 ? s.substring(0, 40) : s) // 너무 긴 개체명 자르기
                .limit(20) // 최대 개수 제한
                .collect(Collectors.toList());
    }
}