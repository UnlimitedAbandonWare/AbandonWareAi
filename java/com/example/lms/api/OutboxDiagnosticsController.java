package com.example.lms.api;

// MERGE_HOOK:PROJ_AGENT::OUTBOX_DIAGNOSTICS_ENDPOINT_V1

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simple monitoring surface for the degraded memory outbox.
 *
 * <p>Intended for internal diagnostics / dashboards. Security is expected to be enforced
 * at the API gateway / ingress layer.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/nova/outbox")
public class OutboxDiagnosticsController {

    private final ObjectProvider<DegradedStorage> storageProvider;

    public OutboxDiagnosticsController(ObjectProvider<DegradedStorage> storageProvider) {
        this.storageProvider = storageProvider;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        DegradedStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "reason", "No DegradedStorage bean present"
            ));
        }

        if (storage instanceof DegradedStorageWithAck ack) {
            return ResponseEntity.ok(ack.stats());
        }

        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "type", storage.getClass().getName(),
                "note", "Storage does not implement DegradedStorageWithAck; only basic drain is available"
        ));
    }

    @PostMapping("/sweep")
    public ResponseEntity<?> sweep() {
        DegradedStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return ResponseEntity.ok(Map.of(
                    "ok", false,
                    "reason", "No DegradedStorage bean present"
            ));
        }

        if (storage instanceof DegradedStorageWithAck ack) {
            return ResponseEntity.ok(ack.sweep());
        }

        return ResponseEntity.ok(Map.of(
                "ok", false,
                "type", storage.getClass().getName(),
                "note", "Storage does not implement DegradedStorageWithAck; sweep is unavailable"
        ));
    }

    // MERGE_HOOK:PROJ_AGENT::OUTBOX_PEEK_ENDPOINT_V1
    @GetMapping("/peek")
    public ResponseEntity<?> peek(
            @RequestParam(defaultValue = "pending") String state,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "256") int maxSnippetChars
    ) {
        DegradedStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "reason", "No DegradedStorage bean present"
            ));
        }

        if (storage instanceof DegradedStorageWithAck ack) {
            // keep this endpoint reasonably bounded
            int safeLimit = Math.max(0, Math.min(limit, 200));
            int safeSnippet = Math.max(0, Math.min(maxSnippetChars, 4096));
            return ResponseEntity.ok(ack.peek(state, safeLimit, safeSnippet));
        }

        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "type", storage.getClass().getName(),
                "note", "Storage does not implement DegradedStorageWithAck; peek is unavailable"
        ));
    }
}
