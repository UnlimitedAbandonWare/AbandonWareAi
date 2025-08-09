package com.example.lms.api;
import java.time.LocalDateTime;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;


import java.util.Optional;
import java.util.ArrayList;   // ⬅ NEW


import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.RuleEngine;

import org.springframework.beans.factory.annotation.Value;
import com.example.lms.service.NaverSearchService;
import jakarta.annotation.PostConstruct;            // (스프링 부트 3.x)
import jakarta.validation.Valid;
// import
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Objects;
import java.util.Collections;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Comparator;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatApiController {

    // 기본/선호 모델: 인스턴스 재접속 시에도 유지될 안전한 기본값
    private static final String FALLBACK_MODEL = "lc:OpenAiChatModel";
    private static final String KEY_DEFAULT_MODEL = "DEFAULT_MODEL";
    // 대화창에 노출되지 않는 숨김 메타 프리픽스(컨트롤러/클라이언트가 필터링)
    private static final String MODEL_META_PREFIX = "⎔MODEL⎔";
    private static final String TRACE_META_PREFIX = "⎔TRACE⎔";
    // 레거시 호환용(과거 세션에 저장된 형태)
    private static final String LEGACY_MODEL_META_PREFIX = "[MODEL] ";
    // ★ 레거시 v0.9 계열(현재 DB에 보이는 '?MODEL?' 등)도 전부 숨김으로 처리
    private static final String LEGACY_MODEL_META_PREFIX_Q = "?MODEL?";
    private static final String LEGACY_TRACE_META_PREFIX_Q = "?TRACE?";
    // FE에서 커스텀 헤더를 읽을 수 있게 노출할 목록
    private static final String EXPOSE_HEADERS = "X-Model-Used,X-RAG-Used,X-User,X-Session-Owner";
    private final ChatHistoryService historyService;
    private final ChatService chatService;
    private final AdaptiveTranslationService adaptiveService;
    private final SettingsService settingsService;
    private final TranslationService translationService;
    private final NaverSearchService searchService;   // ← DI
    // 필드 (추가)
    // 불필요 필드 정리: 메모리는 서비스 계층/체인에서 세션별로 관리

    // … (세션 조회/삭제 메서드 생략) …

    @PostMapping
    public Mono<ResponseEntity<ChatResponseDto>> chat(@RequestBody @Valid ChatRequestDto req,
                                                      @AuthenticationPrincipal UserDetails principal) {
        // 공통 username 추출 (응답 헤더에 내려 FE 배치 문제 최소화)
        String username = principal != null ? principal.getUsername() : "anonymousUser";

        // 1) Adaptive 모드 바로 리턴
        if (req.isUseAdaptive()) {
            return adaptiveService.translate(req.getMessage(), "ko", "en")
                    .map(t -> new ChatResponseDto(t, null, "Adaptive-Translator", false))
                    .map(body -> ResponseEntity.ok()
                            .header("X-Model-Used", "Adaptive-Translator")
                            .header("X-User", username)
                            .header("Access-Control-Expose-Headers", EXPOSE_HEADERS)
                            .body(body));
        }

        // 2) LLM / DB 호출은 블로킹 → boundedElastic

        return Mono.fromCallable(() -> {
                    ChatResponseDto body = handleChat(req, username);
                    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
                    // 모델명은 항상 내려준다(비어 있으면 FALLBACK)
                    ok.header("X-Model-Used",
                            (body.getModelUsed() == null || body.getModelUsed().isBlank())
                                    ? FALLBACK_MODEL
                                    : body.getModelUsed());
                    if (body.isRagUsed()) {
                        ok.header("X-RAG-Used", "true");
                    }
                    ok.header("X-User", username);
                    ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
                    return ok.body(body);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("chat() 처리 오류", ex);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ChatResponseDto("Error: " + ex.getMessage(), null, "error-model", false)));
                });
    }

    /* -------------------------------------------------------------- */
    /*  내부 로직                                                     */
    /* -------------------------------------------------------------- */
    private ChatResponseDto handleChat(ChatRequestDto uiReq, String username) {
        // 1) 설정 병합
        ChatRequestDto dto = mergeWithSettings(uiReq);

        // 2) 세션 upsert
        ChatSession session = uiReq.getSessionId() == null
                ? historyService.startNewSession(dto.getMessage(), username)
                .orElseThrow(() -> new IllegalStateException("세션 생성 실패"))
                : historyService.getSessionWithMessages(uiReq.getSessionId());

        /* ----------------------------------------------------
         * ❶ "기존 세션"이면 USER 발화를 즉시 저장
         *    (새 세션은 startNewSession 내부에서 이미 저장됨)
         * --------------------------------------------------- */
        if (uiReq.getSessionId() != null) {
            historyService.appendMessage(session.getId(), "user", dto.getMessage());
        }
        // 3) 웹 검색 스니펫 수집 (top‑5)  추적
        NaverSearchService.SearchResult sr = dto.isUseWebSearch()
                ? searchService.searchWithTrace(dto.getMessage(), 5)
                : new NaverSearchService.SearchResult(List.of(), null);
        // 4) LLM 호출 (RAG / Memory 처리 포함)
        //    새 세션의 경우 dto.sessionId가 null이므로, 세션별 캐시 일관성을 위해 반드시 실제 sessionId로 교체
        ChatRequestDto dtoForCall = ChatRequestDto.builder()
                .sessionId(session.getId())
                .message(dto.getMessage())
                .history(dto.getHistory())
                .model(dto.getModel())
                .temperature(dto.getTemperature())
                .topP(dto.getTopP())
                .frequencyPenalty(dto.getFrequencyPenalty())
                .presencePenalty(dto.getPresencePenalty())
                .useRag(dto.isUseRag())
                .useWebSearch(dto.isUseWebSearch())
                .build();

        // 4) LLM 호출 (웹 검색 결과를 인자로 전달하여 중복 검색 방지)
        ChatService.ChatResult result = chatService.continueChat(dtoForCall, q -> sr.snippets());

        // assistant 메시지 저장(순수 답변만 저장)
        historyService.appendMessage(session.getId(), "assistant", result.content());

        // 4-1) 모델 메타 저장(숨김): 비어오면 dto.model → FALLBACK 로 보정해서 반드시 저장
        String modelUsedFinal = (result.modelUsed() != null && !result.modelUsed().isBlank())
                ? result.modelUsed()
                : (dto.getModel() != null && !dto.getModel().isBlank() ? dto.getModel() : FALLBACK_MODEL);
        historyService.appendMessage(session.getId(), "system", MODEL_META_PREFIX + modelUsedFinal);

        // 5-1) 검색과정 패널 저장(숨김) + 즉시 응답에는 붙여서 제공(클라이언트 미구현 대비)
        if (dto.isUseWebSearch() && sr.trace() != null) {
            String traceHtml = searchService.buildTraceHtml(sr.trace(), sr.snippets());
            // 숨김 메타로 DB 저장 → 세션 재진입 시 메시지 목록에 섞이지 않음
            historyService.appendMessage(session.getId(), "system", TRACE_META_PREFIX + traceHtml);

            // (선택) 레거시 마커 감지 로그: 세션 엔티티의 messages가 null일 수 있으므로 null-safe 처리
            Optional.ofNullable(session.getMessages())
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(m -> "system".equals(m.getRole()))
                    .map(m -> m.getContent())
                    .filter(c -> c != null && (
                            c.startsWith(LEGACY_MODEL_META_PREFIX_Q) ||
                                    c.startsWith(LEGACY_TRACE_META_PREFIX_Q)))
                    .findAny()
                    .ifPresent(c -> log.debug("Found legacy meta marker; normalized by adding new marker"));
            // 즉시 응답에는 말풍선 뒤에 패널을 덧붙여서 보여줌(이전 동작과 동일)
            return new ChatResponseDto(
                    result.content() + "\n\n" + traceHtml,
                    session.getId(),
                    modelUsedFinal,
                    result.ragUsed()
            );
        }

        // 6) 검색 패널이 없으면 순수 답변만
        return new ChatResponseDto(
                result.content(),
                session.getId(),
                modelUsedFinal,
                result.ragUsed()
        );
    }

    /* -------------------------------------------------------------- */
    /*  Settings 병합                                                 */
    /* -------------------------------------------------------------- */
    private ChatRequestDto mergeWithSettings(ChatRequestDto ui) {
        Map<String, String> cfg = settingsService.getAllSettings();
        Map<String, String> dirty = new HashMap<>();

        double temperature = firstNonNull(ui.getTemperature(), cfg.get(SettingsService.KEY_TEMPERATURE), 0.7);
        double topP = firstNonNull(ui.getTopP(), cfg.get(SettingsService.KEY_TOP_P), 1.0);
        double frequencyPenalty = firstNonNull(ui.getFrequencyPenalty(), cfg.get(SettingsService.KEY_FREQUENCY_PENALTY), 0.0);
        double presencePenalty = firstNonNull(ui.getPresencePenalty(), cfg.get(SettingsService.KEY_PRESENCE_PENALTY), 0.0);

        String model = Optional.ofNullable(ui.getModel()).filter(s -> !s.isBlank())
                .orElse(cfg.getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL));

        trackChange(cfg, SettingsService.KEY_TEMPERATURE, temperature, dirty);
        trackChange(cfg, SettingsService.KEY_TOP_P, topP, dirty);
        trackChange(cfg, SettingsService.KEY_FREQUENCY_PENALTY, frequencyPenalty, dirty);
        trackChange(cfg, SettingsService.KEY_PRESENCE_PENALTY, presencePenalty, dirty);
        // NOTE: 기본/선호 모델은 system 메타 메시지로 세션에 기록하며,
        //       전역 기본값 저장이 필요하면 SettingsService.saveAll(dirty) 구현 후 활성화

        return ChatRequestDto.builder()
                .sessionId(ui.getSessionId())
                .message(ui.getMessage())
                .history(ui.getHistory())
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .useRag(ui.isUseRag())
                .useWebSearch(ui.isUseRag() || ui.isUseWebSearch())
                .build();
    }

    /* -------------------------------------------------------------- */
    /*  세션/메시지 기타 API (생략‑부분은 기존 그대로)                  */
    /* -------------------------------------------------------------- */

    @GetMapping("/sessions")
    public List<SessionInfo> sessions(@AuthenticationPrincipal UserDetails principal) {
        boolean isAdmin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String user = principal != null ? principal.getUsername() : "anonymousUser";
        List<ChatSession> list = isAdmin ? historyService.getAllSessionsForAdmin()
                : historyService.getSessionsForUser(user);
        return list.stream().map(s -> new SessionInfo(s.getId(), s.getTitle())).toList();
    }

    /* -------------------------------------------------------------- */
    /*  헬퍼                                                           */
    /* -------------------------------------------------------------- */
    private static <T> T firstNonNull(T uiVal, String dbVal, T defVal) {
        if (uiVal != null) return uiVal;
        if (dbVal != null) {
            if (defVal instanceof Number) return (T) Double.valueOf(dbVal);
            return (T) dbVal;
        }
        return defVal;
    }

    private static void trackChange(Map<String, String> cfg, String key, Object newVal, Map<String, String> dirty) {
        String current = cfg.get(key);
        String fresh = String.valueOf(newVal);
        if (!Objects.equals(current, fresh)) dirty.put(key, fresh);
    }


    private <T> void overrideIfPresent(T val, String key, Map<String, String> dirty, Consumer<T> setter) {
        if (val != null) {
            setter.accept(val);
            dirty.put(key, String.valueOf(val));
        }
    }

    private double parseOr(Double uiVal, String dbVal, double defVal) {
        return (uiVal != null) ? uiVal : (dbVal != null ? Double.parseDouble(dbVal) : defVal);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id,
                                              Authentication authentication) {

        String username = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName() : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);

        if (!isAdmin && (username == null
                || !session.getAdministrator().getUsername().equals(username))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        historyService.deleteSession(id);   // ← 서비스 계층에서 실제 삭제
        return ResponseEntity.noContent().build();   // 204
    }




    /* ----- 보조 메서드 -------------------------------------------------- */
    private static boolean isOwnerOrAdmin(UserDetails principal, ChatSession s) {
        if (principal == null) return false;
        boolean admin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return admin || principal.getUsername().equals(s.getAdministrator().getUsername());
    }

    /* ----- DTO record defs --------------------------------------------- */
    public record MessageDto(String role, String content, LocalDateTime timestamp) {
    }

    // 🔹 modelUsed를 바디에 포함시켜 FE가 헤더를 안 읽어도 복원 가능
    public record SessionDetail(Long id, String title, LocalDateTime createdAt, List<MessageDto> messages, String modelUsed) {
    }

    public record SessionInfo(Long id, String title) {
    }

    /**
     * GET /api/chat/sessions/{id} → all messages for that session
     **/
    @GetMapping("/sessions/{id}")
    public ResponseEntity<SessionDetail> getSession(
            @PathVariable Long id,
            Authentication authentication
    ) {
        // security: only admins or owner can peek
        String username = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);

        // if non-admin, make sure they only fetch their own sessions:
        if (!isAdmin && !session.getAdministrator().getUsername().equals(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 1) 전체 메시지를 시간순으로 정렬(엔티티 @OrderBy 누락 대비)
        var raw = Optional.ofNullable(session.getMessages())
                .orElse(Collections.emptyList())
                .stream()
                .sorted(Comparator.comparing(m -> m.getCreatedAt()))
                .toList();

        // 2) 숨김 메타 제외 + 메타를 "직전 assistant"에 즉시 매핑(TRACE/ MODEL 모두)
        List<MessageDto> messages = new ArrayList<>();
        int lastAssistantIdx = -1;
        for (var m : raw) {
            String role = m.getRole();
            String content = m.getContent();
            if ("system".equals(role)) {
                // MODEL 메타 → 직전 assistant 머리말에 "model: ..." 1회만 주입
                String mdl = extractModelUsed(content);
                if (mdl != null && lastAssistantIdx >= 0) {
                    MessageDto prev = messages.get(lastAssistantIdx);
                    String body = (prev.content() == null ? "" : prev.content());
                    if (!body.startsWith("model: ")) {
                        messages.set(lastAssistantIdx,
                                new MessageDto(prev.role(), "model: " + mdl  + "\n" + body, prev.timestamp()));
                    }
                }
                // TRACE 메타 → 직전 assistant 말풍선 뒤에 판 재부착(턴별 1:1)
                String trace = extractTraceHtml(content);
                if (trace != null && lastAssistantIdx >= 0) {
                    MessageDto prev = messages.get(lastAssistantIdx);
                    String merged = (prev.content() == null ? "" : prev.content()) + "\n\n" + trace;
                    messages.set(lastAssistantIdx, new MessageDto(prev.role(), merged, prev.timestamp()));
                }
                continue; // system 메타 자체는 리스트에 노출하지 않음
            }
            // 일반 메시지
            messages.add(new MessageDto(role, content, m.getCreatedAt()));
            if ("assistant".equals(role)) lastAssistantIdx = messages.size() - 1;
        }


        // 3) (헤더/요약용) 마지막 모델 메타만 추려서 effectiveModel 계산
        String modelUsed = Optional.ofNullable(session.getMessages())
                .orElse(Collections.emptyList())
                .stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(m -> extractModelUsed(m.getContent()))
                .filter(Objects::nonNull)
                .reduce((prev, cur) -> cur)
                .orElse(null);
        String effectiveModel = (modelUsed == null || modelUsed.isBlank()) ? FALLBACK_MODEL : modelUsed;

// 🔹 안전장치: 마지막 assistant에 model 라인이 없으면 한 번만 주입(중복 방지)
        if (effectiveModel != null && !messages.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                MessageDto msg = messages.get(i);
                if ("assistant".equals(msg.role())) {
                    String current = msg.content() == null ? "" : msg.content();
                    if (!current.startsWith("model: ")) { // 중복 방지
                        String merged = "model: "+  effectiveModel + "\n" + current;
                        messages.set(i, new MessageDto(msg.role(), merged, msg.timestamp()));
                    }
                    break;
                }
            }
        }

        SessionDetail detail = new SessionDetail(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                messages,
                effectiveModel
        );

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        // 🔹 헤더에도 계속 노출(기존 FE 호환)
        ok.header("X-Model-Used", effectiveModel);
        // FE에서 닉네임/배지 복원을 위해 사용자 정보도 헤더로 제공
        String owner = session.getAdministrator().getUsername();
        ok.header("X-Session-Owner", owner);
        ok.header("X-User", owner);
        ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        return ok.body(detail);

    }

    /** -------------------- 메타 파서/필터 헬퍼 -------------------- **/
    private static boolean isHiddenMeta(String c) {
        return c.startsWith(MODEL_META_PREFIX)
                || c.startsWith(LEGACY_MODEL_META_PREFIX)
                || c.startsWith(LEGACY_MODEL_META_PREFIX_Q)
                || c.startsWith(TRACE_META_PREFIX)
                || c.startsWith(LEGACY_TRACE_META_PREFIX_Q);
    }

    private static String extractModelUsed(String c) {
        if (c == null) return null;
        if (c.startsWith(MODEL_META_PREFIX)) return c.substring(MODEL_META_PREFIX.length());
        if (c.startsWith(LEGACY_MODEL_META_PREFIX)) return c.substring(LEGACY_MODEL_META_PREFIX.length());
        if (c.startsWith(LEGACY_MODEL_META_PREFIX_Q)) return c.substring(LEGACY_MODEL_META_PREFIX_Q.length());
        return null;
    }

    private static String extractTraceHtml(String c) {
        if (c == null) return null;
        if (c.startsWith(TRACE_META_PREFIX)) return c.substring(TRACE_META_PREFIX.length());
        if (c.startsWith(LEGACY_TRACE_META_PREFIX_Q)) return c.substring(LEGACY_TRACE_META_PREFIX_Q.length());
        return null;
    }

}
