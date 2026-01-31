package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.context.ContextBridge;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.List;
import java.util.stream.Collectors;




/**
 * Global exception handler that converts {@link ConsentRequiredException}
 * into a Kakao v2.0 basicCard JSON payload. This allows the client to show
 * a consent card to the end user rather than surfacing a raw exception.
 */
@ControllerAdvice
public class ConsentExceptionHandler {

    private final ConsentCardRenderer renderer;
    private final ContextBridge contextBridge;

    public ConsentExceptionHandler(ConsentCardRenderer renderer, ContextBridge contextBridge) {
        this.renderer = renderer;
        this.contextBridge = contextBridge;
    }

    @ExceptionHandler(ConsentRequiredException.class)
    public ResponseEntity<String> handle(ConsentRequiredException ex, HttpServletRequest req) {
        // Try to pull session/room from headers; fall back to ContextBridge if absent.
        String sessionId = safe(req.getHeader("X-Session-Id"));
        String roomId = safe(req.getHeader("X-Kakao-Room-Id"));
        if ((sessionId == null || roomId == null) && contextBridge != null) {
            var current = contextBridge.current();
            if (current != null) {
                if (sessionId == null) sessionId = current.sessionId();
                if (roomId == null)    roomId    = current.roomId();
            }
        }

        List<String> scopes = ex.getMissingScopes()
                .stream().map(Enum::name).collect(Collectors.toList());

        // Render the stock consent card JSON
        String[] actions = scopes.toArray(new String[0]);
        long ttlSeconds = 60L * 60 * 24;
        String json = renderer.renderBasic(contextBridge.sessionId(), contextBridge.roomId(), actions, ttlSeconds);

        return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    private static String safe(String s) { return (s == null || s.isBlank()) ? null : s; }
}