package com.example.lms.api;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.dto.FeedbackDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.web.ClientOwnerKeyResolver;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@RestController
@RequestMapping("/api/chat")
public class FeedbackController {
    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final MemoryReinforcementService memoryService;
    private final ChatHistoryService historyService;
    private final ClientOwnerKeyResolver ownerKeyResolver;
    private final FeedbackMutationGuard mutationGuard;

    @Autowired
    public FeedbackController(
            MemoryReinforcementService memoryService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver,
            FeedbackMutationGuard mutationGuard) {
        this.memoryService = memoryService;
        this.historyService = historyService;
        this.ownerKeyResolver = ownerKeyResolver;
        this.mutationGuard = mutationGuard;
    }

    FeedbackController(
            MemoryReinforcementService memoryService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver) {
        this(memoryService, historyService, ownerKeyResolver, new FeedbackMutationGuard());
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(
            @Valid @RequestBody FeedbackDto req,
            Authentication authentication) {
        if (req == null) {
            return ResponseEntity.badRequest().body("missing_feedback");
        }
        ResponseEntity<?> invalid = validateFeedback(req);
        if (invalid != null) {
            return invalid;
        }
        SessionAuthorization authorization = authorizeFeedbackSession(req.sessionId(), authentication);
        if (authorization.denied() != null) {
            return authorization.denied();
        }
        ChatMessage ratedMessage = req.messageId() == null
                ? latestMatchingAssistant(authorization.session(), req.message())
                : assistantById(authorization.session(), req.messageId());
        if (ratedMessage == null || ratedMessage.getId() == null) {
            if (req.messageId() != null) {
                traceFeedbackRejected("rated_record_not_found", req.sessionId());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("rated_record_not_found");
            }
            traceFeedbackRejected("feedback_target_mismatch", req.sessionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("feedback_target_mismatch");
        }
        if (!Objects.equals(req.message(), ratedMessage.getContent())) {
            traceFeedbackRejected("feedback_target_mismatch", req.sessionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("feedback_target_mismatch");
        }

        String normalizedRating = req.rating().toUpperCase(Locale.ROOT);
        FeedbackMutationGuard.Decision decision = mutationGuard.accept(
                authorization.ownerIdentity(),
                req.sessionId(),
                ratedMessage.getId(),
                ratedMessage.getContent(),
                normalizedRating,
                req.corrected());
        if (decision == FeedbackMutationGuard.Decision.REPLAY) {
            return ResponseEntity.ok().build();
        }
        if (decision == FeedbackMutationGuard.Decision.IN_PROGRESS) {
            traceFeedbackRejected("feedback_in_progress", req.sessionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("feedback_in_progress");
        }
        if (decision == FeedbackMutationGuard.Decision.CAPACITY) {
            traceFeedbackRejected("feedback_capacity", req.sessionId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("feedback_capacity");
        }
        if (decision == FeedbackMutationGuard.Decision.CONFLICT) {
            traceFeedbackRejected("feedback_conflict", req.sessionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body("feedback_conflict");
        }
        try {
            boolean positive = "POSITIVE".equals(normalizedRating);
            memoryService.applyFeedbackToRatedAssistant(
                    String.valueOf(req.sessionId()),
                    ratedMessage.getId(),
                    SafeRedactor.hashValue(ratedMessage.getContent()),
                    ratedMessage.getContent(),
                    positive,
                    req.corrected()
            );
            mutationGuard.commit(
                    authorization.ownerIdentity(),
                    req.sessionId(),
                    ratedMessage.getId(),
                    ratedMessage.getContent(),
                    normalizedRating,
                    req.corrected());
            return ResponseEntity.ok().build();
        } catch (FeedbackMutationGuard.DurableCommitException receiptFailure) {
            log.error("[AWX][feedback] durable receipt commit failed type={}",
                    receiptFailure.getClass().getSimpleName());
            traceFeedbackRejected("feedback_receipt_unavailable", req.sessionId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("feedback_receipt_unavailable");
        } catch (MemoryReinforcementService.RatedRecordNotFoundException missingRecord) {
            mutationGuard.abort(
                    authorization.ownerIdentity(),
                    req.sessionId(),
                    ratedMessage.getId(),
                    ratedMessage.getContent(),
                    normalizedRating,
                    req.corrected());
            traceFeedbackRejected("rated_record_not_found", req.sessionId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("rated_record_not_found");
        } catch (Exception e) {
            mutationGuard.abort(
                    authorization.ownerIdentity(),
                    req.sessionId(),
                    ratedMessage.getId(),
                    ratedMessage.getContent(),
                    normalizedRating,
                    req.corrected());
            log.error("[AWX][feedback] failed type={} errorHash={} errorLength={}",
                    e.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            return ResponseEntity.badRequest().body(publicFeedbackError(e));
        }
    }

    public ResponseEntity<?> feedback(FeedbackDto req) {
        return feedback(req, null);
    }

    static String publicFeedbackError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        return "feedback error: errorCode=feedback_failed"
                + " errorHash=" + SafeRedactor.hashValue(message)
                + " errorLength=" + message.length();
    }

    private SessionAuthorization authorizeFeedbackSession(Long sessionId, Authentication authentication) {
        if (sessionId == null) {
            traceFeedbackRejected("missing_session", null);
            return SessionAuthorization.denied(
                    ResponseEntity.badRequest().body("missing_session"));
        }
        ChatSession session;
        try {
            session = historyService.getSessionWithMessages(sessionId);
        } catch (Exception e) {
            log.warn("[AWX][feedback] session authorization failed sessionHash={} errorType={}",
                    SafeRedactor.hashValue(String.valueOf(sessionId)),
                    e.getClass().getSimpleName());
            traceFeedbackRejected("session_forbidden", sessionId);
            return SessionAuthorization.denied(
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden"));
        }
        if (session == null) {
            traceFeedbackRejected("session_not_found", sessionId);
            return SessionAuthorization.denied(
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body("session_not_found"));
        }
        if (session.getAdministrator() != null) {
            String owner = session.getAdministrator().getUsername();
            if (authentication == null
                    || !authentication.isAuthenticated()
                    || !Objects.equals(owner, authentication.getName())) {
                traceFeedbackRejected("session_forbidden", sessionId);
                return SessionAuthorization.denied(
                        ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden"));
            }
            return SessionAuthorization.authorized(session, "admin\0" + owner);
        }
        String currentOwnerKey;
        try {
            currentOwnerKey = ownerKeyResolver.ownerKey();
        } catch (RuntimeException unavailable) {
            traceFeedbackRejected("session_forbidden", sessionId);
            return SessionAuthorization.denied(
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden"));
        }
        if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentOwnerKey)) {
            traceFeedbackRejected("session_forbidden", sessionId);
            return SessionAuthorization.denied(
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body("session_forbidden"));
        }
        return SessionAuthorization.authorized(session, "anon\0" + currentOwnerKey);
    }

    private static ResponseEntity<?> validateFeedback(FeedbackDto req) {
        if (req.sessionId() == null) {
            traceFeedbackRejected("missing_session", null);
            return ResponseEntity.badRequest().body("missing_session");
        }
        if (req.messageId() != null && req.messageId() <= 0L) {
            traceFeedbackRejected("invalid_message_id", req.sessionId());
            return ResponseEntity.badRequest().body("invalid_feedback");
        }
        if (req.message() == null || req.message().length() > 4000) {
            traceFeedbackRejected("invalid_message", req.sessionId());
            return ResponseEntity.badRequest().body("invalid_feedback");
        }
        if (req.corrected() != null && req.corrected().length() > 4000) {
            traceFeedbackRejected("invalid_correction", req.sessionId());
            return ResponseEntity.badRequest().body("invalid_feedback");
        }
        String rating = req.rating();
        if (rating == null
                || !("POSITIVE".equalsIgnoreCase(rating) || "NEGATIVE".equalsIgnoreCase(rating))) {
            traceFeedbackRejected("invalid_rating", req.sessionId());
            return ResponseEntity.badRequest().body("invalid_feedback");
        }
        return null;
    }

    private static ChatMessage latestMatchingAssistant(ChatSession session, String ratedContent) {
        List<ChatMessage> messages = session == null ? null : session.getMessages();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null
                    && "assistant".equalsIgnoreCase(message.getRole())
                    && Objects.equals(ratedContent, message.getContent())) {
                return message;
            }
        }
        return null;
    }

    private static ChatMessage assistantById(ChatSession session, Long messageId) {
        List<ChatMessage> messages = session == null ? null : session.getMessages();
        if (messageId == null || messages == null || messages.isEmpty()) {
            return null;
        }
        for (ChatMessage message : messages) {
            if (message != null
                    && Objects.equals(messageId, message.getId())
                    && "assistant".equalsIgnoreCase(message.getRole())) {
                return message;
            }
        }
        return null;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }

    private static void traceFeedbackRejected(String reason, Long sessionId) {
        TraceStore.put("api.feedback.rejected", true);
        TraceStore.inc("api.feedback.rejected.count");
        TraceStore.put("api.feedback.skipped.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("api.feedback.sessionHash",
                sessionId == null ? "" : SafeRedactor.hashValue(String.valueOf(sessionId)));
    }

    private record SessionAuthorization(
            ChatSession session,
            String ownerIdentity,
            ResponseEntity<?> denied) {

        private static SessionAuthorization authorized(ChatSession session, String ownerIdentity) {
            return new SessionAuthorization(session, ownerIdentity, null);
        }

        private static SessionAuthorization denied(ResponseEntity<?> response) {
            return new SessionAuthorization(null, null, response);
        }
    }
}
