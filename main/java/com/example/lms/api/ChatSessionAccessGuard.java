package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class ChatSessionAccessGuard {
    private ChatSessionAccessGuard() {
    }

    static ResponseEntity<ChatResponseDto> authorize(
            ChatHistoryService historyService,
            Long sessionId,
            String username,
            String ownerKey,
            Logger log) {
        if (sessionId == null) {
            return null;
        }
        ChatSession session = historyService.getSessionWithMessages(sessionId, 1);
        if (session == null || canAccess(session, username, ownerKey)) {
            return null;
        }
        if (log != null) {
            log.warn("[AWX][chat][session] rejected foreign session sessionHash={}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)));
        }
        GuardContext guardContext = GuardContextHolder.get();
        if (guardContext != null) {
            guardContext.recordInteractionPolicyFact(
                    new InteractionEvidencePolicy.ManipulationFact(
                            InteractionEvidencePolicy.ManipulationKind.UNAUTHORIZED_ACCESS,
                            InteractionEvidencePolicy.ProofKind.AUTHORIZATION_DENIED,
                            InteractionEvidencePolicy.DetectorRule.SESSION_AUTHORIZATION_V1,
                            InteractionEvidencePolicy.SourceSurface.SESSION_HISTORY),
                    null);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ChatResponseDto("session_forbidden", sessionId, "forbidden", false));
    }

    private static boolean canAccess(ChatSession session, String username, String ownerKey) {
        var owner = session.getAdministrator();
        if (owner != null) {
            return username != null && owner.getUsername().equals(username);
        }
        return session.getOwnerKey() != null && session.getOwnerKey().equals(ownerKey);
    }
}
