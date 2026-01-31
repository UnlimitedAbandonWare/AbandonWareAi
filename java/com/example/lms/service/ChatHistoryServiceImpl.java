// src/main/java/com/example/lms/service/ChatHistoryServiceImpl.java
package com.example.lms.service;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.example.lms.domain.enums.MemoryProfile;

@Service
@Primary
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryServiceImpl.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AdministratorRepository administratorRepository;

    private final ObjectMapper objectMapper;

    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
    @org.springframework.beans.factory.annotation.Value("${history.skip-weak-assistant:false}")
    private boolean skipWeakAssistant;

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    // [NEW] IP 해싱용 Salt (게스트 세션 식별 강화용)
    private static final String GUEST_IP_SALT = "jammini-projection-salt-v1";

    // [NEW] 게스트 판별 헬퍼
    private boolean isGuest(String username) {
        return username == null
                || username.isBlank()
                || "anonymousUser".equals(username);
    }

    // [NEW] 게스트 ownerKey 생성 로직 (쿠키 → IP 해시 → UUID 순)
        private String resolveGuestOwnerKey(String fallbackIp) {
        // 1) 쿠키 기반 ownerKey 시도 (요청 스레드에서만)
        try {
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null) {
                String cookieKey = ownerKeyResolver.ownerKey();
                if (cookieKey != null && !cookieKey.isBlank()) {
                    return cookieKey;
                }
            }
        } catch (RuntimeException e) {
            log.debug("ownerKeyResolver 실패: {}", e.getMessage());
        }

        // 2) IP 해시 기반 ownerKey 시도
        if (fallbackIp != null
                && !fallbackIp.isBlank()
                && !"unknown".equalsIgnoreCase(fallbackIp)) {
            try {
                return com.example.lms.util.HashUtil.sha256(fallbackIp + GUEST_IP_SALT);
            } catch (Exception ignore) {
                // 해시 실패 시 다음 단계로 폴백
            }
        }

        // 3) 최후 수단: 랜덤 UUID
        return java.util.UUID.randomUUID().toString();
    }


    /* -------------------- META PREFIXES -------------------- */
    private static final String TRACE_META_PREFIX = "⎔TRACE⎔";
    private static final String TRACE_META_PREFIX_B64 = "⎔TRACE64⎔";
    private static final String LEGACY_TRACE_META_PREFIX_Q = "?TRACE?";
    // Prefix for understanding summary meta.
    private static final String USUM_META_PREFIX = "⎔USUM⎔";
    // Prefix for rolling summary meta.
    private static final String RSUM_META_PREFIX = "⎔RSUM⎔";

    /*
     * =======================================================
     * HTML 차단 정책
     * - TRACE 메타(위 3종)는 무조건 저장
     * - 그 외 system 메시지의 '의도치 않은 생 HTML'만 차단
     * =======================================================
     */
    private static boolean isTraceMeta(String content) {
        if (content == null)
            return false;
        return content.startsWith(TRACE_META_PREFIX)
                || content.startsWith(TRACE_META_PREFIX_B64)
                || content.startsWith(LEGACY_TRACE_META_PREFIX_Q)
                || content.startsWith(USUM_META_PREFIX);
    }

    /** Used in prompt-history formatting filters (UI/meta messages). */
    private static boolean isMetaMessage(String content) {
        if (content == null) return false;
        return isTraceMeta(content) || content.startsWith(RSUM_META_PREFIX);
    }
/** 아주 느슨한 생 HTML 감지 (TRACE 메타는 선별에서 이미 제외) */
    private static boolean looksLikeRawHtml(String content) {
        if (content == null || content.isBlank())
            return false;
        String c = content;
        return c.contains("<div") || c.contains("<span")
                || c.contains("<table") || c.contains("<a ")
                || c.contains("<ul") || c.contains("<ol")
                || c.contains("<li") || c.contains("<p>")
                || c.contains("</");
    }

    // [NEW] 허용 역할 화이트리스트
    private static final Set<String> ALLOWED_ROLES = Set.of("user", "assistant", "system");

    private void save(ChatMessage msg) {
        messageRepository.save(msg);
    }

    private ChatMessage saveReturning(Long sessionId, String role, String content) {
        if (sessionId == null) {
            return null;
        }
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.debug("[History] saveReturning: session not found (id={})", sessionId);
            return null;
        }
        ChatMessage msg = new ChatMessage(session, role, content);
        try {
            return messageRepository.save(msg);
        } catch (Exception e) {
            log.debug("[History] saveReturning failed (sessionId={}): {}", sessionId, e.toString());
            return null;
        }
    }

    private void save(Long sessionId, String role, String content) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + sessionId));
        ChatMessage msg = new ChatMessage(session, role, content);
        save(msg);
    }

    /* -------------------- Session life-cycle -------------------- */

    @Override
    @Transactional
    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    public Optional<ChatSession> startNewSession(String firstMessage, String username, String clientIp) {
        // Allow both admin-owned and guest sessions.
        String safe = java.util.Objects.toString(firstMessage, "");
        String title = safe.length() > 20 ? safe.substring(0, 20) + "/* ... */" : safe;

        ChatSession session;

        if (isGuest(username)) {
            // 게스트 세션: 쿠키 → IP 해시 → UUID 순으로 ownerKey 결정
            String ownerKey = resolveGuestOwnerKey(clientIp);

            session = new ChatSession(title);
            session.setOwnerKey(ownerKey);
            session.setOwnerType("ANON");
            session = sessionRepository.save(session);

            String masked = (ownerKey == null)
                    ? "null"
                    : ownerKey.substring(0, Math.min(8, ownerKey.length())) + "...";
            log.info("익명 게스트 세션 시작 (Hybrid): ownerKey={} title='{}' (id={})",
                    masked, title, session.getId());
        } else {
            Administrator admin = administratorRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalArgumentException("관리자를 찾을 수 없습니다: " + username));
            session = sessionRepository.save(new ChatSession(title, admin));
            log.info("{} 관리자가 세션을 시작했습니다. title='{}' (id={})", username, title, session.getId());
        }

        // 첫 사용자 메시지 즉시 저장
        save(new ChatMessage(session, "user", safe));
        log.debug("세션 {}: 첫 사용자 메시지 저장 완료", session.getId());

        return Optional.of(session);
    }

    /* -------------------- Message utilities -------------------- */

    @Override
    @Transactional
    public void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage) {
        save(new ChatMessage(session, "user", Objects.toString(userMessage, "")));
        save(new ChatMessage(session, "assistant", Objects.toString(assistantMessage, "")));
        log.debug("세션 {}: 대화 페어 저장 완료", session.getId());
    }

    @Override
    @Transactional
    public void appendMessage(Long sessionId, String role, String content) {
        String r = Objects.toString(role, "").toLowerCase(Locale.ROOT).trim();
        String c = Objects.toString(content, "");

        // [CHANGED] 역할 정규화 & 검증
        if (!ALLOWED_ROLES.contains(r)) {
            log.debug("Unsupported role '{}' → skip persist (session {})", role, sessionId);
            return;
        }

        // Skip weak assistant drafts like "정보 없음" when toggle is on
        try {
            if (skipWeakAssistant && "assistant".equals(r)) {
                // 진짜 템플릿 거절만 스킵
                if (EvidenceAwareGuard.looksStructurallyEmpty(c)
                        && EvidenceAwareGuard.looksNoEvidenceTemplate(c)) {
                    log.debug("[History] No-evidence template suppressed → skip persist (session {})", sessionId);
                    return;
                }
            }
        } catch (Throwable ignore) {
            // defensive: guard에서 예외가 나더라도 히스토리 저장은 막지 않는다.
        }

        // 1) TRACE 메타(system)면 무조건 저장
        if ("system".equals(r) && isTraceMeta(c)) {
            save(sessionId, r, c);
            log.debug("세션 {}: system meta 저장 ({} bytes)", sessionId, c.length());
            return;
        }

        // 2) TRACE가 아닌데 system에 생 HTML로 보이면 차단
        if ("system".equals(r) && looksLikeRawHtml(c)) {
            log.debug("raw HTML detected → skip persist (non-trace, session {})", sessionId);
            return;
        }

        // 3) 일반 저장
        save(sessionId, r, c);
        log.debug("세션 {}: {} 메시지 저장", sessionId, r);
    }

    @Override
    @Transactional
    public Long appendMessageReturningId(Long sessionId, String role, String content) {
        String r = Objects.toString(role, "").toLowerCase(Locale.ROOT).trim();
        String c = Objects.toString(content, "");

        if (!ALLOWED_ROLES.contains(r)) {
            log.debug("Unsupported role '{}' → skip persist (session {})", role, sessionId);
            return null;
        }

        // Skip weak assistant drafts like "정보 없음" when toggle is on
        try {
            if (skipWeakAssistant && "assistant".equals(r)) {
                if (EvidenceAwareGuard.looksStructurallyEmpty(c)
                        && EvidenceAwareGuard.looksNoEvidenceTemplate(c)) {
                    log.debug("[History] No-evidence template suppressed → skip persist (session {})", sessionId);
                    return null;
                }
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        // 1) TRACE 메타(system)면 무조건 저장
        if ("system".equals(r) && isTraceMeta(c)) {
            ChatMessage saved = saveReturning(sessionId, r, c);
            return (saved != null) ? saved.getId() : null;
        }

        // 2) TRACE가 아닌데 system에 생 HTML로 보이면 차단
        if ("system".equals(r) && looksLikeRawHtml(c)) {
            log.debug("raw HTML detected → skip persist (non-trace, session {})", sessionId);
            return null;
        }

        // 3) 일반 저장
        ChatMessage saved = saveReturning(sessionId, r, c);
        return (saved != null) ? saved.getId() : null;
    }

    @Override
    @Transactional
    public void updateSessionAnswerModeAndTrace(Long sessionId, String answerMode, Long traceTurnId) {
        if (sessionId == null) return;

        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;

        String mode = (answerMode != null && !answerMode.isBlank()) ? answerMode.trim() : null;

        // 1) Cached columns (cheap for /api/chat/sessions)
        session.setLastAnswerMode(mode);
        session.setLastTraceTurnId(traceTurnId);

        // 2) Also persist into sessionMeta JSON (debug + future-proof)
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        String raw = session.getSessionMeta();
        if (raw != null && !raw.isBlank()) {
            try {
                meta = objectMapper.readValue(raw, new TypeReference<java.util.Map<String, Object>>() {
                });
                if (meta == null) meta = new java.util.LinkedHashMap<>();
            } catch (Exception ignore) {
                meta = new java.util.LinkedHashMap<>();
            }
        }

        if (mode != null) {
            meta.put("lastAnswerMode", mode);
        } else {
            meta.remove("lastAnswerMode");
        }
        if (traceTurnId != null) {
            meta.put("lastTraceTurnId", traceTurnId);
        } else {
            meta.remove("lastTraceTurnId");
        }

        try {
            session.setSessionMeta(objectMapper.writeValueAsString(meta));
        } catch (Exception ignore) {
            // fail-soft
        }

        try {
            sessionRepository.save(session);
        } catch (Exception ignore) {
            // fail-soft
        }
    }

    /* -------------------- Query helpers -------------------- */

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getAllSessionsForAdmin() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsForUser(String username) {
        // 레거시 호출은 IP 정보를 모름 → null 전달
        return getSessionsForUser(username, null);
    }

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsForUser(String username, String clientIp) {
        // 로그인 사용자 (관리자 포함)
        if (!isGuest(username)) {
            return sessionRepository.findByAdministrator_UsernameOrderByCreatedAtDesc(username);
        }

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();

        // A) 쿠키 ownerKey
        try {
            String cookieKey = ownerKeyResolver.ownerKey();
            if (cookieKey != null && !cookieKey.isBlank()) {
                keys.add(cookieKey);
            }
        } catch (RuntimeException e) {
            log.debug("Guest cookie resolution failed: {}", e.toString());
        }

        // B) IP 해시 기반 ownerKey (쿠키 생성 전 세션 포함)
        if (clientIp != null
                && !clientIp.isBlank()
                && !"unknown".equalsIgnoreCase(clientIp)) {
            try {
                keys.add(com.example.lms.util.HashUtil.sha256(clientIp + GUEST_IP_SALT));
            } catch (Exception e) {
                log.debug("Guest IP hash failed: {}", e.toString());
            }
        }

        if (keys.isEmpty()) {
            log.debug("getSessionsForUser: no ownerKey candidates for anonymous user");
            return java.util.List.of();
        }

        log.debug("getSessionsForUser (guest): keys={}", keys);

        java.util.List<ChatSession> sessions = sessionRepository.findByOwnerKeyInOrderByCreatedAtDesc(keys);
        sessions.sort(java.util.Comparator.comparing(ChatSession::getCreatedAt).reversed());
        return sessions;
    }

    public ChatSession getSessionWithMessages(Long id) {
        ChatSession session = sessionRepository.findById(id).orElse(null);
        if (session == null) {
            log.warn("getSessionWithMessages: session {} not found; returning null", id);
            return null;
        }

        // createdAt ASC 보장 (동률 시 id ASC)
        List<ChatMessage> list = messageRepository.findBySessionIdOrderByCreatedAtAsc(id);
        list.sort(Comparator
                .comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChatMessage::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        session.setMessages(list);
        return session;
    }

    @Override
    @Transactional
    public void deleteSession(Long id) {
        sessionRepository.deleteById(id);
        log.info("세션 {} 삭제 완료", id);
    }

    /* -------------------- Formatting -------------------- */

    @Override
    @Transactional(readOnly = true)
    public List<String> getFormattedRecentHistory(Long sessionId, int limit) {
        if (sessionId == null)
            return List.of();
        List<ChatMessage> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size()).stream()
                // [FIX] Exclude TRACE/USUM meta messages from prompt-history formatting
                // to avoid contaminating model context with UI-only HTML blobs.
                .filter(m -> {
                    String c = (m.getContent() == null ? "" : m.getContent());
                    return !isMetaMessage(c);
                })
                .map(m -> {
                    String rawRole = (m.getRole() == null ? "user" : m.getRole());
                    String r = rawRole.trim().toLowerCase(Locale.ROOT);
                    String label;
                    switch (r) {
                        case "user" -> label = "User";
                        case "assistant" -> label = "Assistant";
                        case "system" -> label = "System";
                        default -> label = rawRole;
                    }
                    String content = (m.getContent() == null ? "" : m.getContent());
                    return label + ": " + content;
                })
                .collect(Collectors.toList());
    }

    /* -------------------- Quick access -------------------- */

    // [NEW] 최근 assistant 1건 바로 조회 (createdAt 우선, id DESC 폴백)
    @Override
    @Transactional(readOnly = true)
    public Optional<String> getLastAssistantMessage(Long sessionId) {
        if (sessionId == null)
            return Optional.empty();
        return messageRepository
                .findTopBySessionIdAndRoleOrderByCreatedAtDesc(sessionId, "assistant")
                .or(() -> messageRepository.findTopBySessionIdAndRoleOrderByIdDesc(sessionId, "assistant"))
                .map(ChatMessage::getContent);
    }

        @Override
    @Transactional
    public Optional<ChatSession> startNewSession(
            String firstMessage,
            String username,
            String clientIp,
            String preResolvedOwnerKey,
            MemoryProfile memoryProfile) {

        // Fast-path: SSE/reactive 환경에서 caller가 미리 ownerKey를 캡처한 경우
        // ownerKeyResolver 호출 없이 즉시 세션 생성
        if (isGuest(username)
                && preResolvedOwnerKey != null
                && !preResolvedOwnerKey.isBlank()) {

            String safe = Objects.toString(firstMessage, "");
            String title = safe.length() > 20 ? safe.substring(0, 20) + "/* ... */" : safe;
            String ownerKey = preResolvedOwnerKey.trim();

            ChatSession session = new ChatSession(title);
            session.setOwnerKey(ownerKey);
            session.setOwnerType("ANON");
            if (memoryProfile != null) {
                session.setMemoryProfile(memoryProfile);
            }
            session = sessionRepository.save(session);

            String masked = ownerKey.substring(0, Math.min(8, ownerKey.length())) + "...";
            log.info("익명 게스트 세션 시작 (Hybrid): ownerKey={} title='{}' (id={})",
                    masked, title, session.getId());

            // 첫 사용자 메시지 즉시 저장
            save(new ChatMessage(session, "user", safe));
            log.debug("세션 {}: 첫 사용자 메시지 저장 완료", session.getId());

            return Optional.of(session);
        }

        // 기존 로직: 3-파라미터 버전 호출 후 필요 시 보정
        Optional<ChatSession> base = startNewSession(firstMessage, username, clientIp);
        if (base.isEmpty()) {
            return base;
        }

        ChatSession session = base.get();
        boolean dirty = false;

        // ownerKey 보정 (관리자 세션에는 적용하지 않음)
        if (session.getAdministrator() == null
                && preResolvedOwnerKey != null
                && !preResolvedOwnerKey.isBlank()) {
            String trimmed = preResolvedOwnerKey.trim();
            if (!trimmed.equals(session.getOwnerKey())) {
                session.setOwnerKey(trimmed);
                dirty = true;
            }
        }

        // MemoryProfile 보정
        if (memoryProfile != null && memoryProfile != session.getMemoryProfile()) {
            session.setMemoryProfile(memoryProfile);
            dirty = true;
        }

        if (dirty) {
            session = sessionRepository.save(session);
        }

        return Optional.of(session);
    }


}