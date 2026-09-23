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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.example.lms.domain.enums.MemoryProfile;
import com.example.lms.trace.SafeRedactor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Primary
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryServiceImpl.class);
    private static final long MAX_SESSIONS_PER_OWNER = 200L;
    private final Object sessionQuotaReservationLock = new Object();
    private final Map<String, Integer> pendingSessionCreatesByOwnerHash = new HashMap<>();

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = suppressedErrorType(failure);
        try {
            com.example.lms.search.TraceStore.put("history.suppressed." + safeStage, true);
            com.example.lms.search.TraceStore.put("history.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[History] suppressed trace failed stage={} errorType={}",
                    safeStage, traceFailure.getClass().getSimpleName());
        }
    }

    private static String suppressedErrorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure.getClass().getSimpleName();
    }

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AdministratorRepository administratorRepository;

    private final ObjectMapper objectMapper;

    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
    @org.springframework.beans.factory.annotation.Value("${history.skip-weak-assistant:false}")
    private boolean skipWeakAssistant;
    @org.springframework.beans.factory.annotation.Value("${memory.summary.max-chars:1200}")
    private int rollingSummaryMaxChars;
    @org.springframework.beans.factory.annotation.Value("${memory.summary.promote-min-turns:6}")
    private int rollingSummaryPromoteMinTurns;
    @org.springframework.beans.factory.annotation.Value("${memory.summary.promote-token-threshold:900}")
    private int rollingSummaryPromoteTokenThreshold;
    @org.springframework.beans.factory.annotation.Value("${memory.summary.promote-sentence-threshold:18}")
    private int rollingSummaryPromoteSentenceThreshold;
    @org.springframework.beans.factory.annotation.Value("${memory.summary.anchor-count:12}")
    private int rollingSummaryAnchorCount;
    @org.springframework.beans.factory.annotation.Value("${memory.summary.important-sentences:6}")
    private int rollingSummaryImportantSentenceCount;

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
            log.debug("[History] ownerKeyResolver failed errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }

        // 2) IP 해시 기반 ownerKey 시도
        if (fallbackIp != null
                && !fallbackIp.isBlank()
                && !"unknown".equalsIgnoreCase(fallbackIp)) {
            try {
                return com.example.lms.util.HashUtil.sha256(fallbackIp + GUEST_IP_SALT);
            } catch (Exception ignore) {
                traceSuppressed("history.guestOwnerHash", ignore);
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
    private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";
    // Prefix for understanding summary meta.
    private static final String USUM_META_PREFIX = "⎔USUM⎔";
    // Prefix for rolling summary meta.
    private static final String RSUM_META_PREFIX = "⎔RSUM⎔";
    private static final Set<String> ANCHOR_STOPWORDS = Set.of(
            "user", "assistant", "system", "this", "that", "with", "from", "have", "will",
            "질문", "답변", "내용", "사용자", "이전", "대화", "그리고", "하지만", "대한", "대해",
            "합니다", "있습니다", "해주세요", "관련", "정보", "요약");

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
                || content.startsWith(TRACE_SNAPSHOT_META_PREFIX)
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
            log.debug("[History] saveReturning: session not found (sessionHash={})",
                    hash12(String.valueOf(sessionId)));
            return null;
        }
        ChatMessage msg = new ChatMessage(session, role, content);
        try {
            return messageRepository.save(msg);
        } catch (Exception e) {
            log.debug("[History] saveReturning failed sessionHash={} errorHash={} errorLength={}",
                    hash12(String.valueOf(sessionId)),
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return null;
        }
    }

    private void save(Long sessionId, String role, String content) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session_not_found sessionHash=" + hash12(String.valueOf(sessionId))));
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
            try (SessionQuotaReservation ignored = reserveSessionQuota(null, ownerKey)) {
                session = new ChatSession(title);
                session.setOwnerKey(ownerKey);
                session.setOwnerType("ANON");
                session = saveSessionWithinQuota(session, null, ownerKey);
                save(new ChatMessage(session, "user", safe));
            }

            log.info("익명 게스트 세션 시작 (Hybrid): ownerKeyHash={} titleHash={} titleLength={} sessionHash={}",
                    hash12(ownerKey), hash12(title), title.length(), hash12(String.valueOf(session.getId())));
        } else {
            try (SessionQuotaReservation ignored = reserveSessionQuota(username, null)) {
                Administrator admin = administratorRepository.findByUsername(username)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "admin_not_found adminHash=" + hash12(username)));
                session = saveSessionWithinQuota(new ChatSession(title, admin), username, null);
                save(new ChatMessage(session, "user", safe));
            }
            log.info("adminHash={} 관리자가 세션을 시작했습니다. titleHash={} titleLength={} sessionHash={}",
                    hash12(username), hash12(title), title.length(), hash12(String.valueOf(session.getId())));
        }

        log.debug("sessionHash={}: first user message stored", hash12(String.valueOf(session.getId())));

        return Optional.of(session);
    }

    @Transactional
    public ChatSession createEmptyAnonymousSession(String title, String ownerKey) {
        try (SessionQuotaReservation ignored = reserveSessionQuota(null, ownerKey)) {
            return saveSessionWithinQuota(new ChatSession(title, ownerKey, "ANON"), null, ownerKey);
        }
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
            log.debug("Unsupported role roleHash={} roleLength={} → skip persist (sessionHash={})",
                    hash12(role), role == null ? 0 : role.length(), hash12(String.valueOf(sessionId)));
            return;
        }

        // Skip weak assistant drafts like "정보 없음" when toggle is on
        try {
            if (skipWeakAssistant && "assistant".equals(r)) {
                // 진짜 템플릿 거절만 스킵
                if (EvidenceAwareGuard.looksStructurallyEmpty(c)
                        && EvidenceAwareGuard.looksNoEvidenceTemplate(c)) {
                    log.debug("[History] No-evidence template suppressed → skip persist (sessionHash={})", hash12(String.valueOf(sessionId)));
                    return;
                }
            }
        } catch (Throwable ignore) {
            traceSuppressed("history.skipWeakAssistant.append", ignore);
            // defensive: guard에서 예외가 나더라도 히스토리 저장은 막지 않는다.
        }

        // 1) TRACE 메타(system)면 무조건 저장
        if ("system".equals(r) && isTraceMeta(c)) {
            save(sessionId, r, c);
            log.debug("sessionHash={}: system meta 저장 ({} bytes)", hash12(String.valueOf(sessionId)), c.length());
            return;
        }

        // 2) TRACE가 아닌데 system에 생 HTML로 보이면 차단
        if ("system".equals(r) && looksLikeRawHtml(c)) {
            log.debug("raw HTML detected → skip persist (non-trace, sessionHash={})", hash12(String.valueOf(sessionId)));
            return;
        }

        // 3) 일반 저장
        save(sessionId, r, c);
        log.debug("sessionHash={}: {} 메시지 저장", hash12(String.valueOf(sessionId)), r);
    }

    @Override
    @Transactional
    public Long appendMessageReturningId(Long sessionId, String role, String content) {
        String r = Objects.toString(role, "").toLowerCase(Locale.ROOT).trim();
        String c = Objects.toString(content, "");

        if (!ALLOWED_ROLES.contains(r)) {
            log.debug("Unsupported role roleHash={} roleLength={} → skip persist (sessionHash={})",
                    hash12(role), role == null ? 0 : role.length(), hash12(String.valueOf(sessionId)));
            return null;
        }

        // Skip weak assistant drafts like "정보 없음" when toggle is on
        try {
            if (skipWeakAssistant && "assistant".equals(r)) {
                if (EvidenceAwareGuard.looksStructurallyEmpty(c)
                        && EvidenceAwareGuard.looksNoEvidenceTemplate(c)) {
                    log.debug("[History] No-evidence template suppressed → skip persist (sessionHash={})", hash12(String.valueOf(sessionId)));
                    return null;
                }
            }
        } catch (Throwable ignore) {
            traceSuppressed("history.skipWeakAssistant.returningId", ignore);
        }

        // 1) TRACE 메타(system)면 무조건 저장
        if ("system".equals(r) && isTraceMeta(c)) {
            ChatMessage saved = saveReturning(sessionId, r, c);
            return (saved != null) ? saved.getId() : null;
        }

        // 2) TRACE가 아닌데 system에 생 HTML로 보이면 차단
        if ("system".equals(r) && looksLikeRawHtml(c)) {
            log.debug("raw HTML detected → skip persist (non-trace, sessionHash={})", hash12(String.valueOf(sessionId)));
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
                traceSuppressed("history.answerMeta.read", ignore);
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
            traceSuppressed("history.answerMeta.write", ignore);
        }

        try {
            sessionRepository.save(session);
        } catch (Exception ignore) {
            traceSuppressed("history.answerMeta.save", ignore);
        }
    }

    @Override
    @Transactional
    public void updateSessionMeta(Long sessionId, java.util.Map<String, Object> meta) {
        if (sessionId == null) return;

        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;

        try {
            java.util.Map<String, Object> safeMeta = meta == null ? java.util.Map.of() : meta;
            session.setSessionMeta(objectMapper.writeValueAsString(safeMeta));
            sessionRepository.save(session);
        } catch (Exception ignore) {
            traceSuppressed("history.sessionMeta.update", ignore);
        }
    }

    /* -------------------- Query helpers -------------------- */

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getAllSessionsForAdmin() {
        return getAllSessionsForAdmin(DEFAULT_SESSION_LIST_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getAllSessionsForAdmin(int requestedLimit) {
        int limit = ChatHistoryService.clampSessionListLimit(requestedLimit);
        return sessionRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsForUser(String username) {
        // 레거시 호출은 IP 정보를 모름 → null 전달
        return getSessionsForUser(username, null, DEFAULT_SESSION_LIST_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsForUser(String username, int requestedLimit) {
        return getSessionsForUser(username, null, requestedLimit);
    }

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsForUser(String username, String clientIp) {
        return getSessionsForUser(username, clientIp, DEFAULT_SESSION_LIST_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsForUser(
            String username,
            String clientIp,
            int requestedLimit) {
        int limit = ChatHistoryService.clampSessionListLimit(requestedLimit);
        // 로그인 사용자 (관리자 포함)
        if (!isGuest(username)) {
            return sessionRepository.findByAdministrator_UsernameOrderByCreatedAtDesc(
                    username,
                    org.springframework.data.domain.PageRequest.of(0, limit));
        }

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();

        // A) 쿠키 ownerKey
        try {
            String cookieKey = ownerKeyResolver.ownerKey();
            if (cookieKey != null && !cookieKey.isBlank()) {
                keys.add(cookieKey);
            }
        } catch (RuntimeException e) {
            log.debug("[AWX][chat-history] guest-cookie-resolution-failed errorType={}",
                    e.getClass().getSimpleName());
        }

        // B) IP 해시 기반 ownerKey (쿠키 생성 전 세션 포함)
        if (clientIp != null
                && !clientIp.isBlank()
                && !"unknown".equalsIgnoreCase(clientIp)) {
            try {
                keys.add(com.example.lms.util.HashUtil.sha256(clientIp + GUEST_IP_SALT));
            } catch (Exception e) {
                log.debug("[AWX][chat-history] guest-ip-hash-failed errorType={}",
                        e.getClass().getSimpleName());
            }
        }

        if (keys.isEmpty()) {
            log.debug("getSessionsForUser: no ownerKey candidates for anonymous user");
            return java.util.List.of();
        }

        log.debug("getSessionsForUser (guest): keyCount={} keyHashes={}",
                keys.size(), keys.stream().map(ChatHistoryServiceImpl::hash12).toList());

        java.util.List<ChatSession> sessions = sessionRepository.findByOwnerKeyInOrderByCreatedAtDesc(
                keys,
                org.springframework.data.domain.PageRequest.of(0, limit));
        sessions.sort(java.util.Comparator.comparing(ChatSession::getCreatedAt).reversed());
        return sessions.size() <= limit ? sessions : new java.util.ArrayList<>(sessions.subList(0, limit));
    }

    public ChatSession getSessionWithMessages(Long id) {
        ChatSession session = sessionRepository.findById(id).orElse(null);
        if (session == null) {
            log.warn("getSessionWithMessages: sessionHash={} not found; returning null", hash12(String.valueOf(id)));
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
    @Transactional(readOnly = true)
    public ChatSession getSessionWithMessages(Long id, int requestedLimit) {
        ChatSession session = sessionRepository.findById(id).orElse(null);
        if (session == null) {
            log.warn("getSessionWithMessages: sessionHash={} not found; returning null", hash12(String.valueOf(id)));
            return null;
        }

        int limit = ChatHistoryService.clampSessionDetailLimit(requestedLimit);
        List<ChatMessage> list = new java.util.ArrayList<>(
                messageRepository.findNewestWindowBySessionId(
                        id,
                        org.springframework.data.domain.PageRequest.of(0, limit)));
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
        int requestedLimit = Math.max(1, limit);
        int pageSize = ChatHistoryService.clampSessionDetailLimit(requestedLimit);
        List<ChatMessage> visible = new java.util.ArrayList<>();
        // Read newest pages until enough real turns are found. Keep the Java
        // meta predicate so database collation cannot change prefix matching.
        for (int page = 0; visible.size() < requestedLimit; page++) {
            List<ChatMessage> window = messageRepository.findNewestWindowBySessionId(
                    sessionId, org.springframework.data.domain.PageRequest.of(page, pageSize));
            for (ChatMessage message : window) {
                if (!isMetaMessage(message.getContent())) {
                    visible.add(message);
                    if (visible.size() == requestedLimit) {
                        break;
                    }
                }
            }
            if (window.size() < pageSize) {
                break;
            }
        }
        visible.sort(Comparator
                .comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChatMessage::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return visible.stream()
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

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getRollingSummary(Long sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return loadRollingSummary(sessionId)
                .map(RollingSummarySnapshot::summary)
                .filter(s -> s != null && !s.isBlank());
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationMemorySnapshot getConversationMemorySnapshot(Long sessionId) {
        if (sessionId == null) {
            return ConversationMemorySnapshot.empty();
        }
        return loadRollingSummary(sessionId)
                .map(RollingSummarySnapshot::toConversationMemorySnapshot)
                .orElseGet(ConversationMemorySnapshot::empty);
    }

    @Override
    @Transactional
    public void updateRollingSummary(Long sessionId, Long lastMessageId) {
        if (sessionId == null) {
            return;
        }
        Long effectiveLastId = lastMessageId != null ? lastMessageId : latestConversationMessageId(sessionId);
        if (effectiveLastId == null) {
            return;
        }

        RollingSummarySnapshot previous = loadRollingSummary(sessionId).orElse(RollingSummarySnapshot.empty());
        Long afterId = previous.lastMessageId();
        List<ChatMessage> delta;
        if (afterId != null && afterId > 0) {
            delta = messageRepository.findBySession_IdAndIdGreaterThanOrderByIdAsc(
                    sessionId,
                    afterId,
                    org.springframework.data.domain.PageRequest.of(0, 24));
        } else {
            delta = new java.util.ArrayList<>(
                    messageRepository.findBySession_IdOrderByCreatedAtDesc(
                            sessionId,
                            org.springframework.data.domain.PageRequest.of(0, 12)));
            delta.sort(Comparator
                    .comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ChatMessage::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        }

        List<ChatMessage> selectedDelta = delta.stream()
                .filter(m -> m != null && m.getId() != null && m.getId() <= effectiveLastId)
                .toList();
        if (selectedDelta.isEmpty()) {
            return;
        }
        Long processedLastId = selectedDelta.stream()
                .map(ChatMessage::getId)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<String> lines = selectedDelta.stream()
                .filter(ChatHistoryServiceImpl::isConversationMessage)
                .map(ChatHistoryServiceImpl::formatSummaryLine)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        if (lines.isEmpty() && (previous.summary() == null || previous.summary().isBlank())) {
            return;
        }

        int deltaRawChars = lines.stream().mapToInt(String::length).sum();
        int rawCharCount = Math.max(previous.rawCharCount(), 0) + deltaRawChars;
        String merged = mergeSummary(previous.summary(), lines, rollingSummaryMaxChars);
        if (merged == null || merged.isBlank()) {
            return;
        }
        int turns = Math.max(0, previous.turns()) + lines.size();
        List<String> anchors = extractAnchors(merged, rollingSummaryAnchorCount);
        List<String> importantSentences = selectImportantSentences(
                merged,
                anchors,
                rollingSummaryImportantSentenceCount);
        int sentenceCount = splitSentences(merged).size();
        int tokenEstimate = estimateTokens(merged);
        int compressedCharCount = merged.length();
        if (rawCharCount <= 0) {
            rawCharCount = Math.max(compressedCharCount, merged.length());
        }
        double compressionRatio = ratio(compressedCharCount, rawCharCount);
        boolean promoted = shouldPromoteConversationMemory(turns, sentenceCount, tokenEstimate);
        String promotionHash = promoted
                ? hash12(merged + "|" + String.join(",", anchors) + "|" + String.join("\n", importantSentences))
                : "";

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("lastMessageId", processedLastId);
        payload.put("anchors", anchors);
        payload.put("importantSentences", importantSentences);
        payload.put("turns", turns);
        payload.put("sentenceCount", sentenceCount);
        payload.put("tokenEstimate", tokenEstimate);
        payload.put("rawCharCount", rawCharCount);
        payload.put("compressedCharCount", compressedCharCount);
        payload.put("compressionRatio", compressionRatio);
        payload.put("promoted", promoted);
        payload.put("promotionHash", promotionHash);
        payload.put("updatedAt", java.time.Instant.now().toString());
        try {
            save(sessionId, "system", RSUM_META_PREFIX + objectMapper.writeValueAsString(payload) + "\n" + merged);
            com.example.lms.search.TraceStore.put("memory.summary.updated", true);
            com.example.lms.search.TraceStore.put("memory.summary.length", merged.length());
            com.example.lms.search.TraceStore.put("memory.session.turnCount", turns);
            com.example.lms.search.TraceStore.put("memory.session.sentenceCount", sentenceCount);
            com.example.lms.search.TraceStore.put("memory.session.tokenEstimate", tokenEstimate);
            com.example.lms.search.TraceStore.put("memory.session.compressionRatio", compressionRatio);
            com.example.lms.search.TraceStore.put("memory.session.anchorCount", anchors.size());
            com.example.lms.search.TraceStore.put("memory.session.anchorHashes",
                    anchors.stream().map(ChatHistoryServiceImpl::hash12).toList());
            com.example.lms.search.TraceStore.put("memory.session.promoted", promoted);
        } catch (Exception e) {
            log.debug("[History] rolling summary update failed sessionHash={} errorHash={} errorLength={}",
                    hash12(String.valueOf(sessionId)),
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private Optional<RollingSummarySnapshot> loadRollingSummary(Long sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        try {
            return messageRepository
                    .findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
                            sessionId, "system", RSUM_META_PREFIX)
                    .flatMap(m -> parseRollingSummary(m.getContent()));
        } catch (Exception e) {
            log.debug("[History] rolling summary load failed sessionHash={} errorHash={} errorLength={}",
                    hash12(String.valueOf(sessionId)),
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return Optional.empty();
        }
    }

    private Optional<RollingSummarySnapshot> parseRollingSummary(String content) {
        if (content == null || !content.startsWith(RSUM_META_PREFIX)) {
            return Optional.empty();
        }
        String payload = content.substring(RSUM_META_PREFIX.length()).stripLeading();
        if (payload.isBlank()) {
            return Optional.empty();
        }
        try {
            int newline = payload.indexOf('\n');
            String json = newline >= 0 ? payload.substring(0, newline).trim() : payload.trim();
            String bodySummary = newline >= 0 ? payload.substring(newline + 1).strip() : "";
            if (json.isBlank()) {
                return Optional.empty();
            }
            java.util.Map<String, Object> map = objectMapper.readValue(
                    json,
                    new TypeReference<java.util.Map<String, Object>>() {
                    });
            if (map == null) {
                return Optional.empty();
            }
            String summary = !bodySummary.isBlank()
                    ? bodySummary
                    : Objects.toString(map.get("summary"), "").trim();
            int compressedCharCount = readInt(map.get("compressedCharCount"));
            if (compressedCharCount <= 0 && !summary.isBlank()) {
                compressedCharCount = summary.length();
            }
            return Optional.of(new RollingSummarySnapshot(
                    readLong(map.get("lastMessageId")),
                    summary,
                    readStringList(map.get("anchors")),
                    readStringList(map.get("importantSentences")),
                    readInt(map.get("turns")),
                    readInt(map.get("sentenceCount")),
                    readInt(map.get("tokenEstimate")),
                    readInt(map.get("rawCharCount")),
                    compressedCharCount,
                    readDouble(map.get("compressionRatio"), 1.0d),
                    readBoolean(map.get("promoted")),
                    Objects.toString(map.get("promotionHash"), "")));
        } catch (Exception e) {
            log.debug("[History] rolling summary parse failed errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return Optional.empty();
        }
    }

    private Long latestConversationMessageId(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return messageRepository.findBySession_IdOrderByCreatedAtDesc(
                            sessionId,
                            org.springframework.data.domain.PageRequest.of(0, 20))
                    .stream()
                    .filter(ChatHistoryServiceImpl::isConversationMessage)
                    .map(ChatMessage::getId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignore) {
            traceSuppressed("history.latestConversationMessageId", ignore);
            return null;
        }
    }

    private static boolean isConversationMessage(ChatMessage message) {
        if (message == null) {
            return false;
        }
        String role = Objects.toString(message.getRole(), "").trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return false;
        }
        String content = message.getContent();
        return content != null && !content.isBlank() && !isMetaMessage(content);
    }

    private static String formatSummaryLine(ChatMessage message) {
        String role = Objects.toString(message.getRole(), "user").trim().toLowerCase(Locale.ROOT);
        String label = "assistant".equals(role) ? "Assistant" : "User";
        String content = normalizeWhitespace(message.getContent());
        if (content.isBlank()) {
            return "";
        }
        return label + ": " + clip(content, 360);
    }

    private static String mergeSummary(String previous, List<String> newLines, int maxChars) {
        StringBuilder sb = new StringBuilder();
        String prior = normalizeMultiline(previous);
        if (!prior.isBlank()) {
            sb.append(prior);
        }
        if (newLines != null) {
            for (String line : newLines) {
                String normalized = normalizeWhitespace(line);
                if (normalized.isBlank()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(normalized);
            }
        }
        return tailClamp(sb.toString(), maxChars);
    }

    private static String normalizeMultiline(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(value.strip().split("\\R"))
                .map(ChatHistoryServiceImpl::normalizeWhitespace)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String tailClamp(String value, int maxChars) {
        String normalized = normalizeMultiline(value);
        int max = maxChars > 0 ? maxChars : 1200;
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(normalized.length() - max).strip();
    }

    private static String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)).strip() + "...";
    }

    private boolean shouldPromoteConversationMemory(int turns, int sentenceCount, int tokenEstimate) {
        return turns >= Math.max(1, rollingSummaryPromoteMinTurns)
                || sentenceCount >= Math.max(1, rollingSummaryPromoteSentenceThreshold)
                || tokenEstimate >= Math.max(1, rollingSummaryPromoteTokenThreshold);
    }

    private static double ratio(int compressed, int raw) {
        if (raw <= 0) {
            return 1.0d;
        }
        double value = Math.max(0.0d, Math.min(1.0d, compressed / (double) raw));
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static List<String> extractAnchors(String text, int maxAnchors) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        java.util.Map<String, Integer> freq = new java.util.LinkedHashMap<>();
        traceAnchorTokenizerFallback();
        addRegexTokens(freq, text);
        int limit = Math.max(1, maxAnchors);
        return freq.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(java.util.Map.Entry.comparingByKey()))
                .map(java.util.Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    private static void traceAnchorTokenizerFallback() {
        IllegalStateException ignore = new IllegalStateException();
        traceSuppressed("history.anchorTokenizer", ignore);
    }

    private static void addRegexTokens(java.util.Map<String, Integer> freq, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHangul}\\p{L}\\p{Nd}][\\p{IsHangul}\\p{L}\\p{Nd}_-]{1,}")
                .matcher(text);
        while (matcher.find()) {
            addAnchorToken(freq, matcher.group());
        }
    }

    private static void addAnchorToken(java.util.Map<String, Integer> freq, String raw) {
        String token = normalizeAnchorToken(raw);
        if (token.isBlank() || ANCHOR_STOPWORDS.contains(token)) {
            return;
        }
        freq.merge(token, 1, Integer::sum);
    }

    private static String normalizeAnchorToken(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.replaceAll("[^\\p{IsHangul}\\p{L}\\p{Nd}_-]+", "").strip();
        if (token.length() < 2 || token.length() > 40) {
            return "";
        }
        return token.matches(".*\\p{IsHangul}.*") ? token : token.toLowerCase(Locale.ROOT);
    }

    private static List<String> selectImportantSentences(String text, List<String> anchors, int maxSentences) {
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return List.of();
        }
        Set<String> anchorSet = anchors == null
                ? Set.of()
                : anchors.stream()
                        .map(a -> a == null ? "" : a.toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.List<SentenceScore> scored = new java.util.ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            String lower = sentence.toLowerCase(Locale.ROOT);
            long hits = anchorSet.stream().filter(lower::contains).count();
            double recency = (i + 1) / (double) sentences.size();
            double density = Math.min(sentence.length(), 220) / 220.0d;
            scored.add(new SentenceScore(clip(sentence, 280), hits * 10.0d + recency + density, i));
        }
        return scored.stream()
                .sorted(Comparator
                        .comparingDouble(SentenceScore::score)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(SentenceScore::index).reversed()))
                .map(SentenceScore::text)
                .distinct()
                .limit(Math.max(1, maxSentences))
                .toList();
    }

    private static List<String> splitSentences(String value) {
        String normalized = normalizeMultiline(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : normalized.split("\\R+")) {
            for (String part : line.split("(?<=[.!?。！？])\\s+")) {
                String sentence = normalizeWhitespace(part);
                if (!sentence.isBlank()) {
                    out.add(sentence);
                }
            }
        }
        return out;
    }

    private static int estimateTokens(String text) {
        String normalized = normalizeWhitespace(text);
        if (normalized.isBlank()) {
            return 0;
        }
        int lexical = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHangul}\\p{L}\\p{Nd}][\\p{IsHangul}\\p{L}\\p{Nd}_-]*")
                .matcher(normalized);
        while (matcher.find()) {
            lexical++;
        }
        int charEstimate = (int) Math.ceil(normalized.length() / 3.0d);
        return Math.max(lexical, charEstimate);
    }

    private record SentenceScore(String text, double score, int index) {
    }

    private static Long readLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("history.readLong", ignore);
            return null;
        }
    }

    private static int readInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("history.readInt", ignore);
            return 0;
        }
    }

    private static double readDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("history.readDouble", ignore);
            return fallback;
        }
    }

    private static boolean readBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(v -> Objects.toString(v, "").trim())
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return java.util.Arrays.stream(s.split("[,\\n]"))
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .toList();
        }
        return List.of();
    }

    private static String hash12(String value) {
        return com.example.lms.trace.SafeRedactor.hash12(value);
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }

    private record RollingSummarySnapshot(
            Long lastMessageId,
            String summary,
            List<String> anchors,
            List<String> importantSentences,
            int turns,
            int sentenceCount,
            int tokenEstimate,
            int rawCharCount,
            int compressedCharCount,
            double compressionRatio,
            boolean promoted,
            String promotionHash) {
        private static RollingSummarySnapshot empty() {
            return new RollingSummarySnapshot(null, "", List.of(), List.of(), 0, 0, 0, 0, 0, 1.0d, false, "");
        }

        private ConversationMemorySnapshot toConversationMemorySnapshot() {
            return new ConversationMemorySnapshot(
                    lastMessageId,
                    summary,
                    anchors == null ? List.of() : anchors,
                    importantSentences == null ? List.of() : importantSentences,
                    turns,
                    sentenceCount,
                    tokenEstimate,
                    rawCharCount,
                    compressedCharCount,
                    compressionRatio,
                    promoted,
                    promotionHash == null ? "" : promotionHash);
        }
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
            ChatSession session;
            try (SessionQuotaReservation ignored = reserveSessionQuota(null, ownerKey)) {
                session = new ChatSession(title);
                session.setOwnerKey(ownerKey);
                session.setOwnerType("ANON");
                if (memoryProfile != null) {
                    session.setMemoryProfile(memoryProfile);
                }
                session = saveSessionWithinQuota(session, null, ownerKey);

                // 첫 사용자 메시지 즉시 저장
                save(new ChatMessage(session, "user", safe));
            }

            log.info("익명 게스트 세션 시작 (Hybrid): ownerKeyHash={} titleHash={} titleLength={} sessionHash={}",
                    hash12(ownerKey), hash12(title), title.length(), hash12(String.valueOf(session.getId())));

            log.debug("sessionHash={}: first user message stored", hash12(String.valueOf(session.getId())));

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

    private SessionQuotaReservation reserveSessionQuota(String username, String ownerKey) {
        String ownerHash = username != null
                ? AttachmentOwnerIdentity.forAdministrator(username).hash()
                : AttachmentOwnerIdentity.forAnonymous(ownerKey).hash();
        SessionQuotaReservation reservation;
        synchronized (sessionQuotaReservationLock) {
            long persisted = sessionCount(username, ownerKey);
            int pending = pendingSessionCreatesByOwnerHash.getOrDefault(ownerHash, 0);
            if (persisted + pending >= MAX_SESSIONS_PER_OWNER) {
                throw new ChatHistoryService.SessionQuotaExceededException();
            }
            pendingSessionCreatesByOwnerHash.put(ownerHash, pending + 1);
            reservation = new SessionQuotaReservation(ownerHash);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            reservation.releaseAfterTransactionCompletion();
        }
        return reservation;
    }

    private long sessionCount(String username, String ownerKey) {
        return username != null
                ? sessionRepository.countByAdministrator_Username(username)
                : sessionRepository.countByOwnerKey(ownerKey);
    }

    private ChatSession saveSessionWithinQuota(
            ChatSession session,
            String username,
            String ownerKey) {
        try {
            return sessionRepository.save(session);
        } catch (org.springframework.dao.DataIntegrityViolationException
                | org.springframework.dao.OptimisticLockingFailureException conflict) {
            if (sessionCount(username, ownerKey) >= MAX_SESSIONS_PER_OWNER) {
                throw new ChatHistoryService.SessionQuotaExceededException();
            }
            throw conflict;
        }
    }

    private void releaseSessionQuotaReservation(String ownerHash) {
        synchronized (sessionQuotaReservationLock) {
            int pending = pendingSessionCreatesByOwnerHash.getOrDefault(ownerHash, 0);
            if (pending <= 1) {
                pendingSessionCreatesByOwnerHash.remove(ownerHash);
            } else {
                pendingSessionCreatesByOwnerHash.put(ownerHash, pending - 1);
            }
        }
    }

    private final class SessionQuotaReservation implements AutoCloseable {
        private final String ownerHash;
        private final AtomicBoolean released = new AtomicBoolean();
        private boolean transactionManaged;

        private SessionQuotaReservation(String ownerHash) {
            this.ownerHash = ownerHash;
        }

        private void releaseAfterTransactionCompletion() {
            transactionManaged = true;
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        release();
                    }
                });
            } catch (RuntimeException | Error registrationFailure) {
                transactionManaged = false;
                release();
                throw registrationFailure;
            }
        }

        @Override
        public void close() {
            if (!transactionManaged) {
                release();
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                releaseSessionQuotaReservation(ownerHash);
            }
        }
    }


}
