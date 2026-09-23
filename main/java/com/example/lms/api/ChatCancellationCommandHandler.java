package com.example.lms.api;

import com.example.lms.service.chat.ChatRunRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns the bounded outcome policy for one exact chat-run cancellation. */
@Component
public final class ChatCancellationCommandHandler {

    public Result cancel(
            Long sessionId,
            String runToken,
            BooleanSupplier authorized,
            ChatRunRegistry runRegistry,
            Runnable stoppedMarker) {
        if (sessionId == null) {
            return Result.notCancelled("session_id_required");
        }
        if (runToken == null || runToken.isBlank()) {
            return Result.notCancelled("run_not_found_or_not_cancellable");
        }
        Objects.requireNonNull(authorized, "authorized");
        Objects.requireNonNull(stoppedMarker, "stoppedMarker");
        try {
            if (!authorized.getAsBoolean()) {
                return Result.notCancelled("run_not_found_or_not_cancellable");
            }
        } catch (RuntimeException authorizationFailure) {
            return Result.notCancelled("run_not_found_or_not_cancellable");
        }
        try {
            boolean cancelled = runRegistry != null
                    && runRegistry.cancelExact(sessionId, runToken, stoppedMarker);
            return cancelled
                    ? new Result(true, "cancelled")
                    : Result.notCancelled("run_not_found_or_not_cancellable");
        } catch (Exception cancellationFailure) {
            return Result.notCancelled("cancel_failed");
        }
    }

    public record Result(boolean cancelled, String reason) {
        private static Result notCancelled(String reason) {
            return new Result(false, reason);
        }

        public Map<String, Object> body() {
            return Map.of("cancelled", cancelled, "reason", reason);
        }
    }
}
