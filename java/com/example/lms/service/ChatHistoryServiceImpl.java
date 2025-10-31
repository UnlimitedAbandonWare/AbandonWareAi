// src/main/java/com/example/lms/service/ChatHistoryServiceImpl.java
package com.example.lms.service;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
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




@Service
@Primary
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryServiceImpl.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AdministratorRepository administratorRepository;
    
    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
@org.springframework.beans.factory.annotation.Value("${history.skip-weak-assistant:true}")
    private boolean skipWeakAssistant;


    /* -------------------- META PREFIXES -------------------- */
    private static final String TRACE_META_PREFIX          = "⎔TRACE⎔";
    private static final String TRACE_META_PREFIX_B64      = "⎔TRACE64⎔";
    private static final String LEGACY_TRACE_META_PREFIX_Q = "?TRACE?";
    // Prefix for understanding summary meta.
    private static final String USUM_META_PREFIX = "⎔USUM⎔";

    /* =======================================================
     * HTML 차단 정책
     *  - TRACE 메타(위 3종)는 무조건 저장
     *  - 그 외 system 메시지의 '의도치 않은 생 HTML'만 차단
     * ======================================================= */
    private static boolean isTraceMeta(String content) {
        if (content == null) return false;
        return content.startsWith(TRACE_META_PREFIX)
                || content.startsWith(TRACE_META_PREFIX_B64)
                || content.startsWith(LEGACY_TRACE_META_PREFIX_Q)
                || content.startsWith(USUM_META_PREFIX);
    }

    /** 아주 느슨한 생 HTML 감지 (TRACE 메타는 선별에서 이미 제외) */
    private static boolean looksLikeRawHtml(String content) {
        if (content == null || content.isBlank()) return false;
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

    private void save(Long sessionId, String role, String content) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + sessionId));
        ChatMessage msg = new ChatMessage(session, role, content);
        save(msg);
    }

    /* -------------------- Session life-cycle -------------------- */

    @Override
    @Transactional
    
public Optional<ChatSession> startNewSession(String firstMessage, String username, String clientIp) {
    // Allow both admin-owned and guest sessions.
    String safe = java.util.Objects.toString(firstMessage, "");
    String title = safe.length() > 20 ? safe.substring(0, 20) + "/* ... *&#47;" : safe;

    ChatSession session;
    if (username == null || username.isBlank() || "anonymousUser".equals(username)) {
        String ownerKey;
        try {
            ownerKey = ownerKeyResolver.ownerKey();
        } catch (RuntimeException e) {
            // When no HttpServletRequest is bound to the current thread the ClientOwnerKeyResolver
            // will throw an IllegalStateException.  Fall back to a stable identifier derived
            // from the provided client IP.  If the IP is unavailable or hashing fails,
            // generate a random UUID.  This ensures anonymous sessions can still be
            // created during asynchronous processing while maintaining per‑client consistency.
            ownerKey = null;
            if (clientIp != null && !clientIp.isBlank() && !"unknown".equalsIgnoreCase(clientIp)) {
                try {
                    ownerKey = com.example.lms.util.HashUtil.sha256(clientIp);
                } catch (Exception ignore) {
                    ownerKey = null;
                }
            }
            if (ownerKey == null || ownerKey.isBlank()) {
                ownerKey = java.util.UUID.randomUUID().toString();
            }
        }
        session = new ChatSession(title);
        session.setOwnerKey(ownerKey);
        session.setOwnerType("ANON");
        session = sessionRepository.save(session);
        log.info("익명 게스트 세션 시작: ownerKey={} title='{}' (id={})",
                (ownerKey == null ? "null" : ownerKey.substring(0, Math.min(8, ownerKey.length())) + "/* ... *&#47;"),
                title, session.getId());
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
        if (skipWeakAssistant && "assistant".equals(r)) {
            try {
                if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(c)) {
                    log.debug("weak assistant draft suppressed → skip persist (session {})", sessionId);
                    return;
                }
            } catch (Throwable ignore) {}
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

    /* -------------------- Query helpers -------------------- */

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getAllSessionsForAdmin() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    
public List<ChatSession> getSessionsForUser(String username) {
    if (username == null || username.isBlank() || "anonymousUser".equals(username)) {
        String ownerKey = ownerKeyResolver.ownerKey();
        return sessionRepository.findByOwnerKeyOrderByCreatedAtDesc(ownerKey);
    }
    return sessionRepository.findByAdministrator_UsernameOrderByCreatedAtDesc(username);
}


    @Override
    @Transactional(readOnly = true)
    public ChatSession getSessionWithMessages(Long id) {
        ChatSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + id));

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
        if (sessionId == null) return List.of();
        List<ChatMessage> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size()).stream()
                .map(m -> {
                    String role = (m.getRole() == null ? "user" : m.getRole().toLowerCase());
                    String content = (m.getContent() == null ? "" : m.getContent());
                    return role + ": " + content;
                })
                .collect(Collectors.toList());
    }

    /* -------------------- Quick access -------------------- */

    // [NEW] 최근 assistant 1건 바로 조회 (createdAt 우선, id DESC 폴백)
    @Override
    @Transactional(readOnly = true)
    public Optional<String> getLastAssistantMessage(Long sessionId) {
        if (sessionId == null) return Optional.empty();
        return messageRepository
                .findTopBySessionIdAndRoleOrderByCreatedAtDesc(sessionId, "assistant")
                .or(() -> messageRepository.findTopBySessionIdAndRoleOrderByIdDesc(sessionId, "assistant"))
                .map(ChatMessage::getContent);
    }
}