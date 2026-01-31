package com.example.lms.service.diagnostic;

import com.example.lms.diag.RetrievalDiagnosticsCollector;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;





/**
 * DiagnosticsDumpService writes structured operational metrics to disk.  At the
 * end of a chat pipeline, ChatService can invoke this service to record
 * per-session data such as model usage and retrieval performance.  The
 * resulting files are stored under a configurable directory in JSON Lines
 * format.  Each invocation appends a single JSON object to the target file.
 *
 * The implementation intentionally avoids throwing checked exceptions back to
 * the caller; any I/O errors are logged but will not break the chat loop.
 */
@Service
public class DiagnosticsDumpService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticsDumpService.class);

    private final RetrievalDiagnosticsCollector diagnosticsCollector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Base directory for dump files.  This can be overridden via
     * application properties.  Defaults to `/logs/dumps` relative to the
     * working directory.  A trailing slash is optional.
     */
    @Value("${diagnostics.dump.dir:/logs/dumps}")
    private String dumpDir;

    public DiagnosticsDumpService(RetrievalDiagnosticsCollector diagnosticsCollector) {
        this.diagnosticsCollector = diagnosticsCollector;
    }

    /**
     * Record diagnostic metrics for a chat session.  The call is made
     * asynchronously to avoid blocking the main chat thread.  Parameters
     * accept anything the caller can provide; missing values (e.g. tokens
     * consumed) may be left null.  The retrieval diagnostics are pulled
     * directly from the {@link RetrievalDiagnosticsCollector} associated with
     * the current thread.
     *
     * @param sessionId    the logical session identifier
     * @param modelName    the model used to generate the answer
     * @param tokensIn     approximate tokens provided to the model (nullable)
     * @param tokensOut    approximate tokens returned from the model (nullable)
     * @param estimatedCost estimated monetary cost of the call, in USD (nullable)
     * @param selectedDocs identifiers (e.g. URLs or IDs) of the final documents used (nullable)
     * @param verification  result of verification stage (e.g. "SUCCESS", "FAILURE", "MODIFIED")
     * @param userFeedback  optional user feedback string such as 👍/👎 (nullable)
     * @param synergyDelta  change in synergy statistic, if applicable (nullable)
     */
    @Async
    public void dump(String sessionId,
                     String modelName,
                     Long tokensIn,
                     Long tokensOut,
                     Double estimatedCost,
                     List<String> selectedDocs,
                     String verification,
                     String userFeedback,
                     Double synergyDelta) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", Instant.now().toString());
            record.put("sessionId", sessionId);
            // Use traceId from MDC if available; may be null
            String traceId = MDC.get("traceId");
            record.put("traceId", traceId);
            record.put("modelName", modelName);
            record.put("tokensIn", tokensIn);
            record.put("tokensOut", tokensOut);
            record.put("estimatedCost", estimatedCost);
            record.put("verification", verification);
            record.put("userFeedback", userFeedback);
            record.put("synergyDelta", synergyDelta);
            if (selectedDocs != null) {
                record.put("selectedDocs", selectedDocs);
            }
            // Add retrieval diagnostic summary and full dump
            try {
                record.put("retrievalSummary", diagnosticsCollector.summarize());
                record.put("retrievalDetails", diagnosticsCollector.dump());
            } catch (Exception ex) {
                record.put("retrievalSummary", "N/A");
                record.put("retrievalDetails", "N/A");
            }
            // Serialize to JSON and append to a per-session file
            String json = objectMapper.writeValueAsString(record);
            Path dir = Paths.get(dumpDir);
            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    log.warn("Could not create diagnostics dump directory {}: {}", dumpDir, e.getMessage());
                    return;
                }
            }
            Path file = dir.resolve(sessionId + ".jsonl");
            Files.writeString(file, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.warn("Failed to write diagnostics dump for session {}: {}", sessionId, ex.getMessage());
        }
    }
}