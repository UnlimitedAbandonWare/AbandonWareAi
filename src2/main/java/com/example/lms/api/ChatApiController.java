// 경로: src/main/java/com/example/lms/api/ChatApiController.java
package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * REST Controller : /api/chat (최종 통합본)
 * - 기존의 모든 채팅 관련 API 기능을 이 컨트롤러 하나로 통합합니다.
 * - 적응형 번역, 자동 번역, 동적 설정 병합, 세션 관리 등 모든 기능을 지원합니다.
 * - ChatService를 사용하여 DB 모델 설정을 정확히 반영하고, 실제 사용된 모델을 응답에 포함합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatApiController {

    private final ChatService                chatService;
    private final AdaptiveTranslationService adaptiveService;
    private final ChatHistoryService         historyService;
    private final SettingsService            settingsService;
    private final TranslationService         translationService;

    /* ===========================================================
     * GET: 세션 목록 / 세션 상세
     * =========================================================== */

    @GetMapping("/sessions")
    public List<SessionInfo> getAllSessions() {
        return historyService.getAllSessions().stream()
                .map(s -> new SessionInfo(s.getId(), s.getTitle()))
                .collect(Collectors.toList());
    }

    @GetMapping("/sessions/{id}")
    public ChatSession getSession(@PathVariable Long id) {
        return historyService.getSessionWithMessages(id);
    }

    /* ===========================================================
     * POST: 채팅 처리
     * =========================================================== */

    @PostMapping
    public Mono<ResponseEntity<ChatApiResponse>> chat(@RequestBody ChatRequestDto req) {

        Mono<ChatApiResponse> responseMono;

        if (req.isUseAdaptive()) {
            // ① 적응형 번역 모드
            responseMono = adaptiveService.translate(req.getMessage(), "ko", "en")
                    .map(txt -> new ChatApiResponse(null, txt, "Adaptive-Translator"));
        } else {
            // ② GPT 챗봇 모드
            responseMono = Mono.fromCallable(() -> {

                // 1) DB 설정 + UI 설정을 병합하고, 변경된 내용은 즉시 DB에 저장합니다.
                ChatRequestDto finalReq = mergeWithDbSettings(req);

                // 2) (옵션) 사용자 입력 자동 번역
                if (finalReq.isAutoTranslate()) {
                    finalReq.setMessage(translationService.koToEn(finalReq.getMessage()));
                }

                // 3) 새로운 ChatService를 호출하여 응답과 모델명을 받습니다.
                ChatService.ChatResult reply = chatService.continueChat(finalReq);
                String assistantReply = reply.content();

                // 4) (옵션) GPT 응답을 다시 한국어로 번역합니다.
                if (finalReq.isAutoTranslate()) {
                    assistantReply = translationService.enToKo(assistantReply);
                }

                // 5) 세션을 생성/업데이트하고 대화 내용을 저장합니다.
                ChatSession session = upsertSession(req.getSessionId(), req.getMessage());
                historyService.addMessagesToSession(session, req.getMessage(), assistantReply);

                // 6) 최종 응답을 생성합니다. (실제 사용된 모델명 포함)
                return new ChatApiResponse(session.getId(), assistantReply, reply.model());

            }).subscribeOn(Schedulers.boundedElastic());
        }

        return responseMono
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("채팅 처리 중 오류 발생", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ChatApiResponse(req.getSessionId(), "처리 중 오류가 발생했습니다.", "Error")));
                });
    }

    /* ===========================================================
     * Helper methods
     * =========================================================== */

    private ChatSession upsertSession(Long sessionId, String firstMessage) {
        return (sessionId == null)
                ? historyService.startNewSession(firstMessage)
                : historyService.getSessionWithMessages(sessionId);
    }

    private ChatRequestDto mergeWithDbSettings(ChatRequestDto ui) {
        Map<String, String> dbSettings = settingsService.getAllSettings();
        Map<String, String> changedSettings = new HashMap<>();

        ChatRequestDto.ChatRequestDtoBuilder builder = ChatRequestDto.builder()
                .message(ui.getMessage())
                .history(ui.getHistory())
                .model(ui.getModel())
                .autoTranslate(ui.isAutoTranslate())
                .systemPrompt(dbSettings.get(SettingsService.KEY_SYSTEM_PROMPT))
                .temperature(Double.parseDouble(dbSettings.getOrDefault(SettingsService.KEY_TEMPERATURE, "0.7")))
                .topP(Double.parseDouble(dbSettings.getOrDefault(SettingsService.KEY_TOP_P, "1.0")))
                .frequencyPenalty(Double.parseDouble(dbSettings.getOrDefault(SettingsService.KEY_FREQUENCY_PENALTY, "0.0")))
                .presencePenalty(Double.parseDouble(dbSettings.getOrDefault(SettingsService.KEY_PRESENCE_PENALTY, "0.0")));

        overrideIfPresent(ui.getSystemPrompt(),  SettingsService.KEY_SYSTEM_PROMPT, changedSettings, builder::systemPrompt);
        overrideIfPresent(ui.getTemperature(),   SettingsService.KEY_TEMPERATURE, changedSettings, builder::temperature);
        overrideIfPresent(ui.getTopP(),          SettingsService.KEY_TOP_P, changedSettings, builder::topP);
        overrideIfPresent(ui.getFrequencyPenalty(), SettingsService.KEY_FREQUENCY_PENALTY, changedSettings, builder::frequencyPenalty);
        overrideIfPresent(ui.getPresencePenalty(),  SettingsService.KEY_PRESENCE_PENALTY, changedSettings, builder::presencePenalty);

        if (!changedSettings.isEmpty()) {
            log.info("📝 채팅 중 설정 변경 감지 → 즉시 저장: {}", changedSettings);
            settingsService.saveAllSettings(changedSettings);
        }
        return builder.build();
    }

    private <T> void overrideIfPresent(T value, String key, Map<String, String> dirtyMap, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
            dirtyMap.put(key, String.valueOf(value));
        }
    }

    /* ===========================================================
     * DTOs
     * =========================================================== */

    public record SessionInfo(Long id, String title) {}

    // API 요청 DTO는 ChatRequestDto를 그대로 사용하거나,
    // 이 클래스만의 추가 필드가 필요할 경우 상속하여 사용합니다.
    // 현재는 추가 필드가 ChatRequestDto에 통합되었으므로 상속만 받습니다.
    @Getter @Setter
    public static class ChatApiRequest extends ChatRequestDto {}

    @Getter
    public static class ChatApiResponse {
        private final Long   sessionId;
        private final String content;
        private final String model;
        public ChatApiResponse(Long sessionId, String content, String model) {
            this.sessionId = sessionId;
            this.content   = content;
            this.model     = model;
        }
    }
}