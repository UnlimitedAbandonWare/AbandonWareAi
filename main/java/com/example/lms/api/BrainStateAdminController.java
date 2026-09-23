package com.example.lms.api;

import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.GraphRagChunkingService;
import com.example.lms.service.rag.graph.InferenceResult;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/vector/brain")
public class BrainStateAdminController {
    private static final System.Logger LOG = System.getLogger(BrainStateAdminController.class.getName());

    private final GraphRagChunkingService chunkingService;
    private final BrainStateService brainStateService;
    private final FileIngestionService fileIngestionService;

    public BrainStateAdminController(GraphRagChunkingService chunkingService,
                                     BrainStateService brainStateService) {
        this(chunkingService, brainStateService, null);
    }

    @Autowired
    public BrainStateAdminController(GraphRagChunkingService chunkingService,
                                     BrainStateService brainStateService,
                                     FileIngestionService fileIngestionService) {
        this.chunkingService = chunkingService;
        this.brainStateService = brainStateService;
        this.fileIngestionService = fileIngestionService;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphRagChunkingService.IngestReport> ingest(
            @RequestBody(required = false) IngestRequest request) {
        IngestRequest safe = request == null ? new IngestRequest(null, null, null, null, null, null, null) : request;
        if (hasText(safe.text())) {
            return ResponseEntity.ok(chunkingService.ingestText(
                    safe.sessionId(), safe.text(), "MANUAL", safe.domain()));
        }
        if (hasText(safe.userText()) || hasText(safe.assistantText())) {
            return ResponseEntity.ok(chunkingService.ingestConversationTurn(
                    safe.sessionId(), safe.userText(), safe.assistantText()));
        }
        if (hasText(safe.sessionId())) {
            return ResponseEntity.ok(chunkingService.ingestSession(
                    safe.sessionId(), safe.afterMessageId(), safe.limit() == null ? 100 : safe.limit()));
        }
        return ResponseEntity.badRequest().body(GraphRagChunkingService.IngestReport.disabled("", "missing_input"));
    }

    @PostMapping(value = "/chunk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphRagChunkingService.IngestReport> chunk(
            @RequestBody(required = false) ChunkRequest request) {
        if (request == null || !hasText(request.text())) {
            return ResponseEntity.badRequest().body(GraphRagChunkingService.IngestReport.disabled("", "missing_text"));
        }
        return ResponseEntity.ok(chunkingService.ingestText(
                request.sessionId(), request.text(), "MANUAL", request.domain()));
    }

    @PostMapping(value = "/ingest-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GraphRagChunkingService.IngestReport> ingestFile(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "domain", required = false) String domain) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GraphRagChunkingService.IngestReport.disabled(sessionId, "missing_file"));
        }
        if (fileIngestionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(GraphRagChunkingService.IngestReport.disabled(sessionId, "file_ingestion_unavailable"));
        }
        try {
            String text = fileIngestionService.extractText(file.getOriginalFilename(), file.getContentType(), file.getBytes());
            if (!hasText(text)) {
                return ResponseEntity.badRequest()
                        .body(GraphRagChunkingService.IngestReport.disabled(sessionId, "unsupported_or_empty_file"));
            }
            return ResponseEntity.ok(chunkingService.ingestText(sessionId, text, "MANUAL", domain));
        } catch (Exception ex) {
            traceSuppressed("brainState.fileIngestion", ex);
            return ResponseEntity.badRequest()
                    .body(GraphRagChunkingService.IngestReport.disabled(sessionId, "file_ingestion_failed"));
        }
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(brainStateService.status());
    }

    @PostMapping(value = "/infer", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InferenceResult> infer(@RequestBody(required = false) InferRequest request) {
        String query = request == null ? "" : request.query();
        String domain = request == null ? "" : request.domain();
        boolean localOnly = request != null && Boolean.TRUE.equals(request.localOnly());
        InferenceResult result = localOnly
                ? brainStateService.querySparseInferenceLocalOnly(query, domain)
                : brainStateService.querySparseInference(query, domain);
        return ResponseEntity.ok(result);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void traceSuppressed(String stage, Exception failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        try {
            TraceStore.put("api.brainState.suppressed.stage", safeStage);
            TraceStore.put("api.brainState.suppressed.errorType", errorType);
            TraceStore.put("api.brainState.suppressed." + safeStage, true);
            TraceStore.put("api.brainState.suppressed." + safeStage + ".errorType", errorType);
            TraceStore.inc("api.brainState.suppressed.count");
        } catch (Throwable traceFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "BrainState admin trace skipped stage=" + safeStage
                            + " errorType=" + traceFailure.getClass().getSimpleName());
        }
    }

    public record IngestRequest(
            String sessionId,
            String userText,
            String assistantText,
            String text,
            String domain,
            Long afterMessageId,
            Integer limit) {
    }

    public record ChunkRequest(String sessionId, String text, String domain) {
    }

    public record InferRequest(String query, String domain, Boolean localOnly) {
    }
}
