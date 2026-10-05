package com.example.lms.api;

import com.example.lms.api.dto.SearchFeedbackDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.feedback.FeedbackBlocklistRegistry;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.web.ClientOwnerKeyResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.*;



/**
 * REST endpoint for recording user feedback on search results.  When the
 * front-end submits a downvote action this controller stores the
 * associated host, title and reason into the session blocklist via the
 * {@link FeedbackBlocklistRegistry}.  Only downvote actions are
 * recognised; other actions result in a bad request response.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchFeedbackController {
    private final FeedbackBlocklistRegistry registry;
    private final ChatHistoryService historyService;
    private final ClientOwnerKeyResolver ownerKeyResolver;

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody @Valid SearchFeedbackDto dto, Authentication authentication) {
        if (dto == null) {
            traceRejected("missing_payload");
            return ResponseEntity.badRequest().build();
        }
        // Only handle explicit downvote actions
        if (!"downvote".equalsIgnoreCase(dto.getAction())) {
            return ResponseEntity.badRequest().build();
        }
        Long sessionId = parseSessionId(dto.getSessionId());
        if (sessionId == null) {
            traceRejected("invalid_session");
            return ResponseEntity.badRequest().build();
        }
        try {
            var session = historyService.getSessionForRequest(sessionId);
            if (session == null) {
                traceRejected("session_not_found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            String ownerKey = session.getAdministrator() == null ? ownerKeyResolver.ownerKey() : null;
            if (!ChatSessionAccessGuard.canAccess(session, requestUsername(authentication), ownerKey)) {
                traceRejected("session_forbidden");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        } catch (RuntimeException unavailable) {
            traceRejected("session_authorization_unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        registry.downvote(dto.getSessionId(), dto.getHost(), dto.getTitle(), dto.getUrl(), dto.getReason());
        return ResponseEntity.ok().build();
    }

    public ResponseEntity<?> feedback(SearchFeedbackDto dto) {
        return feedback(dto, null);
    }

    private static Long parseSessionId(String raw) {
        if (raw == null) return null;
        String normalized = raw.startsWith("chat-") ? raw.substring(5) : raw;
        if (!normalized.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.valueOf(normalized); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String requestUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        String name = authentication.getName();
        return name == null || name.isBlank() || java.util.Set.of("proto-open", "admin-token").contains(name)
                ? null : name;
    }

    private static void traceRejected(String reason) {
        TraceStore.put("api.searchFeedback.rejected", true);
        TraceStore.inc("api.searchFeedback.rejected.count");
        TraceStore.put("api.searchFeedback.skipped.reason", reason);
    }
}
