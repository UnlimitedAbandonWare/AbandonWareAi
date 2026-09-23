package com.example.lms.agent.context;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/agent/db-context")
@ConditionalOnBean(AgentDbContextProvider.class)
@ConditionalOnProperty(prefix = "agent.db-context", name = "enabled", havingValue = "true")
public class AgentDbContextController {
    private static final Logger log = LoggerFactory.getLogger(AgentDbContextController.class);

    private final AgentDbContextProvider provider;

    public AgentDbContextController(AgentDbContextProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot() {
        return safe("snapshot", provider::snapshot);
    }

    @GetMapping("/memory")
    public ResponseEntity<?> memory() {
        return safe("memory", provider::memorySnapshot);
    }

    @GetMapping("/ledger")
    public ResponseEntity<?> ledger() {
        return safe("ledger", provider::ledgerSnapshot);
    }

    @GetMapping("/strategy")
    public ResponseEntity<?> strategy() {
        return safe("strategy", provider::strategySnapshot);
    }

    private ResponseEntity<?> safe(String endpoint, Supplier<?> supplier) {
        try {
            return ResponseEntity.ok(supplier.get());
        } catch (DataAccessException | IllegalStateException ex) {
            traceSuppressed(endpoint, ex);
            return failSoft(endpoint, ex);
        }
    }

    private static ResponseEntity<?> failSoft(String endpoint, RuntimeException ex) {
        Map<String, Object> body = failSoftBody(endpoint, ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static void traceSuppressed(String endpoint, RuntimeException ex) {
        String safe = safeEndpoint(endpoint);
        try {
            TraceStore.put("agent.dbContext." + safe + ".failSoft", true);
            TraceStore.put("agent.dbContext." + safe + ".reason", "db_context_unavailable");
            TraceStore.put("agent.dbContext." + safe + ".failureClass", failureClass(ex));
            TraceStore.put("agent.dbContext." + safe + ".errorType",
                    ex == null ? "unknown" : ex.getClass().getSimpleName());
        } catch (Throwable traceFailure) {
            log.debug("[AWX][agent][db-context] fail-soft trace failed endpoint={} errorType={}",
                    safe,
                    traceFailure == null ? "unknown" : traceFailure.getClass().getSimpleName());
        }
    }

    static Map<String, Object> failSoftBodyForTest(String endpoint, RuntimeException ex) {
        return failSoftBody(endpoint, ex);
    }

    private static Map<String, Object> failSoftBody(String endpoint, RuntimeException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", safeEndpoint(endpoint));
        body.put("enabled", false);
        body.put("disabledReason", "db_context_unavailable");
        body.put("failureClass", failureClass(ex));
        return body;
    }

    private static String failureClass(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof TransactionTimedOutException
                    || cur instanceof org.springframework.dao.QueryTimeoutException
                    || cur instanceof java.util.concurrent.TimeoutException
                    || cur.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                return "timeout";
            }
            if (cur instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                    || cur instanceof org.springframework.dao.DataAccessResourceFailureException) {
                return "db-unavailable";
            }
            cur = cur.getCause();
        }
        return "db-context-unavailable";
    }

    private static String safeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "unknown";
        }
        String normalized = endpoint.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z0-9_-]{1,32}") ? normalized : "unknown";
    }
}
