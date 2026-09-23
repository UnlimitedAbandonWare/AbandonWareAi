package com.example.lms.api;

import com.example.lms.service.embedding.OllamaEmbeddingModel;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime diagnostics for embedding backends.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET  /api/diagnostics/embedding</li>
 *   <li>POST /api/diagnostics/embedding/reset  (requires configured X-Admin-Token)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diagnostics/embedding")
public class EmbeddingDiagnosticsController {

    private static final System.Logger LOG = System.getLogger(EmbeddingDiagnosticsController.class.getName());

    private final ObjectProvider<OllamaEmbeddingModel> ollamaProvider;
    private final ObjectProvider<DomainProfileLoader> domainProfileLoader;

    public EmbeddingDiagnosticsController(
            ObjectProvider<OllamaEmbeddingModel> ollamaProvider,
            ObjectProvider<DomainProfileLoader> domainProfileLoader
    ) {
        this.ollamaProvider = ollamaProvider;
        this.domainProfileLoader = domainProfileLoader;
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", Instant.now().toString());

        OllamaEmbeddingModel ollama = ollamaProvider.getIfAvailable();
        if (ollama != null) {
            out.put("ollama", ollama.diagnosticsSnapshot());
        } else {
            out.put("ollama", Map.of("available", false));
        }

        return out;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(
            @RequestHeader(value = "X-Admin-Token", required = false) String token
    ) {
        requireAdmin(token);

        OllamaEmbeddingModel ollama = ollamaProvider.getIfAvailable();
        if (ollama != null) {
            ollama.resetFastFail();
        }

        return Map.of("ok", true);
    }

    private void requireAdmin(String presentedToken) {
        DomainProfileLoader loader = domainProfileLoader.getIfAvailable();
        if (loader == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin token required");
        }

        String expected;
        try {
            expected = loader.getAdminToken();
        } catch (Exception ignore) {
            traceSuppressed("embedding.adminToken", ignore);
            expected = null;
        }

        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin token required");
        }

        if (presentedToken == null || presentedToken.isBlank() || !expected.equals(presentedToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin token required");
        }
    }

    private static void traceSuppressed(String stage, Exception failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Embedding diagnostics admin fallback stage={0} errorType={1}",
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }
}
