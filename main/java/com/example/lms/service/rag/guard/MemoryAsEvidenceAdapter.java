package com.example.lms.service.rag.guard;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts session memory and knowledge-base entries into evidence snippets.
 */
@Component
@RequiredArgsConstructor
public class MemoryAsEvidenceAdapter {
    private static final Logger log = LoggerFactory.getLogger(MemoryAsEvidenceAdapter.class);

    private final ChatHistoryService history;

    @Autowired(required = false)
    private KnowledgeBaseService kb;

    public List<String> fromSession(Long sessionId, int lastN) {
        if (sessionId == null) return Collections.emptyList();
        List<String> rawSnippets = new ArrayList<>();
        try {
            ChatSession session = history.getSessionWithMessages(sessionId);
            if (session != null) {
                collectAssistantContents(session.getMessages(), rawSnippets, lastN);
            }
        } catch (Exception e) {
            log.debug("[AWX][rag][evidence] session memory load failed failureReason={} errorType={} sessionHash={}",
                    "memory-session-load-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hashValue(String.valueOf(sessionId)));
        }
        return normalize(rawSnippets);
    }

    public List<String> fromKb(String domain, String subject, int max) {
        if (kb == null) return Collections.emptyList();
        try {
            return normalize(new ArrayList<>(kb.evidenceSnippets(domain, subject, Math.max(1, max))));
        } catch (Exception e) {
            log.debug("[AWX][rag][evidence] kb snippets load failed failureReason={} errorType={} domainHash={} subjectHash={}",
                    "memory-kb-snippets-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hashValue(domain),
                    SafeRedactor.hashValue(subject));
            return Collections.emptyList();
        }
    }

    private List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        return raw.stream()
                .filter(s -> s != null)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#") && s.length() > 1)
                .distinct()
                .collect(Collectors.toList());
    }

    private void collectAssistantContents(List<ChatMessage> messages, List<String> out, int limit) {
        if (messages == null || messages.isEmpty() || out == null) return;
        int cap = Math.max(0, limit);
        if (cap == 0) return;

        List<ChatMessage> copy = new ArrayList<>(messages);
        Collections.reverse(copy);

        List<String> recent = new ArrayList<>();
        for (ChatMessage msg : copy) {
            if (recent.size() >= cap) break;
            if (msg == null || msg.getRole() == null) continue;
            if (!"assistant".equalsIgnoreCase(msg.getRole())) continue;
            String content = msg.getContent();
            if (content != null && !content.isBlank()) {
                recent.add(content);
            }
        }

        Collections.reverse(recent);
        out.addAll(recent);
    }
}
