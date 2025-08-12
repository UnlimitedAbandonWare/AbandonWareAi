package com.example.lms.service.ner;

import com.example.lms.service.correction.DomainTermDictionary;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM과 도메인 사전을 함께 사용하여 텍스트에서 명명 개체(Named Entity)를 추출합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMNamedEntityExtractor implements NamedEntityExtractor {

    private final OpenAiService openAi;
    private final DomainTermDictionary dict;

    @Value("${ner.model:gpt-4o-mini}")
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
        String userPrompt = "TEXT:\n" + text;

        String rawLlmOutput = "";
        try {
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(List.of(
                            new ChatMessage(ChatMessageRole.SYSTEM.value(), sysPrompt),
                            new ChatMessage(ChatMessageRole.USER.value(), userPrompt)
                    ))
                    .temperature(0.0) // 결정적(deterministic) 결과를 위해 0으로 설정
                    .topP(0.05)      // 낮은 값으로 설정하여 더 집중된 결과 유도
                    .build();

            rawLlmOutput = openAi.createChatCompletion(request)
                    .getChoices().get(0).getMessage().getContent();
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