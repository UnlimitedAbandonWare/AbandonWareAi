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

@Slf4j
@Component
@RequiredArgsConstructor
public class LLMNamedEntityExtractor implements NamedEntityExtractor {

    private final OpenAiService openAi;
    private final DomainTermDictionary dict;

    @Value("${ner.model:gpt-4o-mini}")
    private String model;

    @Override
    public List<String> extract(String text) {
        if (!StringUtils.hasText(text)) return List.of();

        // 1) LLM 호출
        String sys = """
                You extract named entities (people, items, places, game characters, organizations) from Korean text.
                Output ONLY a comma-separated list of short entity names. No explanation.
                Example: 푸리나, 원신, 폰타인
                """;
        String user = "TEXT:\n" + text;

        String raw = "";
        try {
            ChatCompletionRequest req = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(List.of(
                            new ChatMessage(ChatMessageRole.SYSTEM.value(), sys),
                            new ChatMessage(ChatMessageRole.USER.value(), user)
                    ))
                    .temperature(0d)
                    .topP(0.05d)
                    .build();
            raw = openAi.createChatCompletion(req)
                    .getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.debug("[NER] LLM call failed: {}", e.toString());
        }

        // 2) 파싱 + 정리
        Set<String> out = new LinkedHashSet<>();
        if (StringUtils.hasText(raw)) {
            for (String t : raw.split("\\s*,\\s*")) {
                String s = t.trim();
                if (s.length() >= 2) out.add(s);
            }
        }

        // 3) 사전에서 발견되는 보호어는 반드시 포함
        try {
            out.addAll(dict.findKnownTerms(text));
        } catch (Exception ignore) {}

        // 4) 길이/수량 정리
        return out.stream()
                .map(s -> s.length() > 40 ? s.substring(0, 40) : s)
                .limit(20)
                .collect(Collectors.toList());
    }
}