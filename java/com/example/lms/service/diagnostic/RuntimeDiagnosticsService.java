package com.example.lms.service.diagnostic;

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.service.VectorStoreService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates "운영 중 복구/차단 상태" signals into one payload.
 *
 * Intentionally avoids any user-content payloads; counters/state only.
 */
@Service
public class RuntimeDiagnosticsService {

    private final ObjectProvider<VectorStoreService> vectorStoreService;
    private final ObjectProvider<NightmareBreaker> nightmareBreaker;
    private final ObjectProvider<DegradedStorage> outboxStorage;

    public RuntimeDiagnosticsService(
            ObjectProvider<VectorStoreService> vectorStoreService,
            ObjectProvider<NightmareBreaker> nightmareBreaker,
            @Qualifier("outboxStorage") ObjectProvider<DegradedStorage> outboxStorage
    ) {
        this.vectorStoreService = vectorStoreService;
        this.nightmareBreaker = nightmareBreaker;
        this.outboxStorage = outboxStorage;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", Instant.now().toString());

        VectorStoreService vs = vectorStoreService.getIfAvailable();
        if (vs != null) {
            root.put("vectorStore", vs.bufferStats());
        } else {
            root.put("vectorStore", Map.of("available", false));
        }

        NightmareBreaker nb = nightmareBreaker.getIfAvailable();
        if (nb != null) {
            root.put("nightmareBreaker", nb.snapshot());
        } else {
            root.put("nightmareBreaker", Map.of("available", false));
        }

        DegradedStorage outbox = outboxStorage.getIfAvailable();
        if (outbox instanceof DegradedStorageWithAck withAck) {
            root.put("outbox", withAck.stats());
        } else if (outbox != null) {
            root.put("outbox", Map.of(
                    "available", true,
                    "type", outbox.getClass().getName(),
                    "withAck", false
            ));
        } else {
            root.put("outbox", Map.of("available", false));
        }

        return root;
    }
}
