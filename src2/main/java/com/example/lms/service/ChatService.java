package com.example.lms.service;

import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /**
     * 결과를 담아 반환하는 DTO
     */
    public record ChatResult(String content, String model) {}

    // --- 의존성 주입 ---
    private final OpenAiService openAiService;
    private final PromptService promptService;
    private final CurrentModelRepository currentRepo;
    private final RuleEngine ruleEngine;
    private final MemoryReinforcementService memoryReinforcementService;

    // --- application.properties 설정값 ---
    @Value("${openai.api.model:gpt-3.5-turbo}")
    private String defaultModel;

    @Value("${openai.fine-tuning.custom-model-id:}")
    private String tunedModelId;

    @Value("${openai.api.temperature.default:0.7}")
    private double defaultTemp;

    @Value("${openai.api.top-p.default:1.0}")
    private double defaultTopP;

    @Value("${openai.api.history.max-messages:10}")
    private int maxHistory;

    /**
     * 통합 채팅 처리 메서드
     */
    public ChatResult continueChat(ChatRequestDto request) {
        // 1) 모델 결정
        String model = determineModel(request.getModel());

        // 2) 메시지 구성 (규칙 전처리 포함)
        List<ChatMessage> messages = buildMessages(request);

        // 3) OpenAI 요청 빌드
        ChatCompletionRequest apiRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(messages)
                .temperature(Optional.ofNullable(request.getTemperature()).orElse(defaultTemp))
                .topP(Optional.ofNullable(request.getTopP()).orElse(defaultTopP))
                .frequencyPenalty(request.getFrequencyPenalty())
                .presencePenalty(request.getPresencePenalty())
                .maxTokens(request.getMaxTokens())
                .build();

        log.info("[ChatService] Request -> model='{}', temp={}, topP={}", model,
                apiRequest.getTemperature(), apiRequest.getTopP());

        try {
            // 4) API 호출 및 응답 처리
            ChatCompletionResult result = openAiService.createChatCompletion(apiRequest);
            String raw = result.getChoices().get(0).getMessage().getContent();
            String usedModel = result.getModel();

            // 5) 후처리 (RULE 엔진)
            String processed = ruleEngine.apply(raw, "ko", RulePhase.POST);
            log.info("[ChatService] Response <- model='{}', preview='{}...'", usedModel,
                    processed.substring(0, Math.min(processed.length(), 60)));

            // 6) 메모리 강화
            reinforceMemory(request);

            return new ChatResult(processed, usedModel);
        } catch (RuntimeException ex) {
            log.error("[ChatService] API 호출 실패: {}", ex.getMessage());
            return new ChatResult("API 호출 중 오류가 발생했습니다: " + ex.getMessage(), model);
        }
    }

    /**
     * WebSocket 등 간단 호출용
     */
    public ChatResult ask(String userMsg) {
        ChatRequestDto req = ChatRequestDto.builder()
                .message(userMsg)
                .systemPrompt(promptService.getSystemPrompt())
                .build();
        return continueChat(req);
    }

    /**
     * 모델 결정 우선순위: 요청 > 파인튜닝 ID > DB 저장값 > 기본값
     */
    private String determineModel(String requested) {
        if (StringUtils.hasText(requested)) return requested;
        if (StringUtils.hasText(tunedModelId)) {
            log.debug("파인튜닝 모델 사용: {}", tunedModelId);
            return tunedModelId;
        }
        return currentRepo.findById(1L)
                .map(CurrentModel::getModelId)
                .orElse(defaultModel);
    }

    /**
     * 메시지 리스트 구성 (SYSTEM, HISTORY, USER 순)
     */
    private List<ChatMessage> buildMessages(ChatRequestDto req) {
        List<ChatMessage> msgs = new ArrayList<>();
        String sys = req.getSystemPrompt();
        if (!StringUtils.hasText(sys)) sys = promptService.getSystemPrompt();
        if (StringUtils.hasText(sys)) {
            msgs.add(new ChatMessage(ChatMessageRole.SYSTEM.value(), sys));
        }

        if (!CollectionUtils.isEmpty(req.getHistory())) {
            req.getHistory().stream()
                    .skip(Math.max(0, req.getHistory().size() - maxHistory))
                    .map(m -> new ChatMessage(m.getRole().toLowerCase(), m.getContent()))
                    .forEach(msgs::add);
        }

        String in = ruleEngine.apply(req.getMessage(), "ko", RulePhase.PRE);
        msgs.add(new ChatMessage(ChatMessageRole.USER.value(), in));
        return msgs;
    }

    /**
     * 기록 기반 메모리 강화
     */
    private void reinforceMemory(ChatRequestDto req) {
        if (StringUtils.hasText(req.getMessage())) {
            memoryReinforcementService.reinforceMemoryWithText(req.getMessage());
        }
        if (!CollectionUtils.isEmpty(req.getHistory())) {
            req.getHistory().stream()
                    .filter(m -> "user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent()))
                    .forEach(m -> memoryReinforcementService.reinforceMemoryWithText(m.getContent()));
        }
    }
}
