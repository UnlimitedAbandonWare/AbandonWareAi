package com.example.lms.api;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.VectorBackendHealthService;
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.entity.VectorQuarantineDlq;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/vector")
@RequiredArgsConstructor
public class VectorAdminController {

    private final VectorSidService vectorSidService;
    private final VectorStoreService vectorStoreService;
    private final EmbeddingStoreManager embeddingStoreManager;
    private final TranslationMemoryRepository memoryRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SidRotationAdvisor sidRotationAdvisor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VectorIngestProtectionService ingestProtectionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VectorBackendHealthService vectorBackendHealthService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VectorQuarantineDlqService vectorQuarantineDlqService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sid", vectorSidService.snapshot());
        out.put("activeGlobalSid", vectorSidService.resolveActiveSid(LangChainRAGService.GLOBAL_SID));
        out.put("quarantineSid", vectorSidService.quarantineSid());
        out.put("buffer", vectorStoreService.bufferStats());
        out.put("rotationAdvisor", sidRotationAdvisor == null ? Map.of("enabled", false) : sidRotationAdvisor.snapshot());
        out.put("ingestProtection", ingestProtectionService == null ? Map.of("enabled", false) : ingestProtectionService.snapshot());
        out.put("vectorBackendHealth", vectorBackendHealthService == null ? Map.of("enabled", false) : vectorBackendHealthService.snapshot());
        out.put("vectorDlq", vectorQuarantineDlqService == null ? Map.of("enabled", false) : vectorQuarantineDlqService.stats());
        return ResponseEntity.ok(out);
    }


    @GetMapping("/ingest-protection")
    public ResponseEntity<Map<String, Object>> ingestProtection() {
        if (ingestProtectionService == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        return ResponseEntity.ok(ingestProtectionService.snapshot());
    }

    @PostMapping("/ingest-protection/clear")
    public ResponseEntity<Map<String, Object>> clearIngestProtection(
            @RequestParam(name = "sid", required = false) String sid
    ) {
        if (ingestProtectionService == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        ingestProtectionService.clearQuarantine(sid);
        return ResponseEntity.ok(ingestProtectionService.snapshot());
    }

    @GetMapping("/dlq")
    public ResponseEntity<Map<String, Object>> dlq() {
        if (vectorQuarantineDlqService == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        Map<String, Object> out = new LinkedHashMap<>(vectorQuarantineDlqService.stats());
        if (vectorBackendHealthService != null) {
            out.put("vectorBackendHealth", vectorBackendHealthService.snapshot());
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/dlq/records")
    public ResponseEntity<Map<String, Object>> dlqRecords(
            @RequestParam(name = "status", required = false) VectorQuarantineDlq.Status status,
            @RequestParam(name = "sidBase", required = false) String sidBase,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {

        if (vectorQuarantineDlqService == null) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "items", List.of(),
                    "page", page,
                    "size", size,
                    "totalElements", 0,
                    "totalPages", 0
            ));
        }

        var p = vectorQuarantineDlqService.listRecords(status, sidBase, page, size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("filters", Map.of(
                "status", status == null ? null : status.name(),
                "sidBase", sidBase
        ));
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("items", p.getContent());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/dlq/records/{id}")
    public ResponseEntity<Map<String, Object>> dlqRecord(
            @PathVariable("id") Long id,
            @RequestParam(name = "includePayload", defaultValue = "false") boolean includePayload) {

        if (vectorQuarantineDlqService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "enabled", false,
                    "error", "vectorQuarantineDlqService not available"
            ));
        }

        VectorQuarantineDlq row = vectorQuarantineDlqService.getById(id);
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "enabled", true,
                    "id", id,
                    "error", "not_found"
            ));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("id", row.getId());
        out.put("status", row.getStatus() == null ? null : row.getStatus().name());
        out.put("attemptCount", row.getAttemptCount());
        out.put("nextAttemptAt", row.getNextAttemptAt());
        out.put("lastAttemptAt", row.getLastAttemptAt());
        out.put("lastError", row.getLastError());
        out.put("originalSid", row.getOriginalSid());
        out.put("originalSidBase", row.getOriginalSidBase());
        out.put("quarantineReason", row.getQuarantineReason());
        out.put("quarantineVectorId", row.getQuarantineVectorId());
        out.put("originalVectorId", row.getOriginalVectorId());
        out.put("lockedBy", row.getLockedBy());
        out.put("lockedAt", row.getLockedAt());
        out.put("createdAt", row.getCreatedAt());
        out.put("updatedAt", row.getUpdatedAt());
        out.put("metaJson", row.getMetaJson());

        if (includePayload) {
            out.put("payload", row.getPayload());
        }

        return ResponseEntity.ok(out);
    }

    @GetMapping("/dlq/reasons")
    public ResponseEntity<Map<String, Object>> dlqReasons(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {

        if (vectorQuarantineDlqService == null) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "blocked", List.of(),
                    "failed", List.of()
            ));
        }

        int safeLimit = Math.min(Math.max(1, limit), 200);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("limit", safeLimit);
        out.put("blocked", vectorQuarantineDlqService.topReasons(VectorQuarantineDlq.Status.BLOCKED, safeLimit));
        out.put("failed", vectorQuarantineDlqService.topReasons(VectorQuarantineDlq.Status.FAILED, safeLimit));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/dlq/redrive")
    public ResponseEntity<VectorQuarantineDlqService.RedriveReport> dlqRedrive(
            @RequestParam(name = "requestedBy", required = false) String requestedBy
    ) {
        if (vectorQuarantineDlqService == null) {
            return ResponseEntity.ok(new VectorQuarantineDlqService.RedriveReport(false, 0, 0, 0, 0, 0, 0, null));
        }
        return ResponseEntity.ok(vectorQuarantineDlqService.redriveDueOnce(requestedBy));
    }

    @PostMapping("/dlq/health/probe")
    public ResponseEntity<Map<String, Object>> dlqHealthProbe() {
        if (vectorBackendHealthService == null) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        vectorBackendHealthService.probeNow();
        return ResponseEntity.ok(vectorBackendHealthService.snapshot());
    }


    @GetMapping("/ingest-audit")
    public ResponseEntity<List<VectorStoreService.IngestAuditEvent>> ingestAudit(
            @RequestParam(name = "limit", defaultValue = "200") int limit
    ) {
        return ResponseEntity.ok(vectorStoreService.getIngestAudit(limit));
    }

    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flush() {
        vectorStoreService.flush();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "buffer", vectorStoreService.bufferStats()
        ));
    }

    @PostMapping("/rotate-sid")
    public ResponseEntity<Map<String, Object>> rotateSid() {
        String prev = vectorSidService.resolveActiveSid(LangChainRAGService.GLOBAL_SID);
        String next = embeddingStoreManager.rotateGlobalSid();
        return ResponseEntity.ok(Map.of(
                "logicalSid", LangChainRAGService.GLOBAL_SID,
                "prevActiveSid", prev,
                "nextActiveSid", next
        ));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<EmbeddingStoreManager.AdminRebuildReport> rebuild(
            @RequestParam(name = "logicalSid", required = false) String logicalSid,
            @RequestParam(name = "kbLimit", defaultValue = "5000") int kbLimit,
            @RequestParam(name = "memoryLimit", defaultValue = "500") int memoryLimit,
            @RequestParam(name = "includeKb", defaultValue = "true") boolean includeKb
    ) {
        EmbeddingStoreManager.AdminRebuildReport rep = embeddingStoreManager.adminRebuild(logicalSid, kbLimit, memoryLimit, includeKb);
        return ResponseEntity.ok(rep);
    }

    /* ------------------------ quarantine CRUD (TranslationMemory) ------------------------ */

    public record QuarantineItem(
            Long id,
            String sessionId,
            String sourceTag,
            String status,
            String createdAt,
            String updatedAt,
            String lastUsedAt,
            String preview
    ) {
    }

    @GetMapping("/quarantine")
    public ResponseEntity<List<QuarantineItem>> listQuarantine(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size
    ) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, 200));
        var pageable = PageRequest.of(p, s, Sort.by(Sort.Order.asc("createdAt")));
        var res = memoryRepo.findByStatusOrderByCreatedAtAsc(TranslationMemory.MemoryStatus.QUARANTINED, pageable);

        List<QuarantineItem> out = (res == null || res.isEmpty())
                ? List.of()
                : res.getContent().stream().map(VectorAdminController::toItem).toList();

        return ResponseEntity.ok(out);
    }

    @GetMapping("/quarantine/{id}")
    public ResponseEntity<QuarantineItem> getQuarantine(@PathVariable("id") Long id) {
        if (id == null) return ResponseEntity.notFound().build();
        Optional<TranslationMemory> opt = memoryRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toItem(opt.get()));
    }

    public record QuarantineUpdate(String status) {
    }

    @PostMapping("/quarantine/{id}")
    public ResponseEntity<Map<String, Object>> updateQuarantine(@PathVariable("id") Long id,
                                                                @RequestBody(required = false) QuarantineUpdate req) {
        if (id == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id is null"));
        TranslationMemory tm = memoryRepo.findById(id).orElse(null);
        if (tm == null) return ResponseEntity.notFound().build();

        String st = (req == null || req.status() == null) ? "" : req.status().trim().toUpperCase();
        if (st.isBlank()) st = "ACTIVE";

        TranslationMemory.MemoryStatus ns;
        try {
            ns = TranslationMemory.MemoryStatus.valueOf(st);
        } catch (Exception e) {
            ns = TranslationMemory.MemoryStatus.ACTIVE;
        }

        tm.setStatus(ns);
        memoryRepo.save(tm);

        return ResponseEntity.ok(Map.of("ok", true, "id", id, "status", ns.name()));
    }

    @DeleteMapping("/quarantine/{id}")
    public ResponseEntity<Map<String, Object>> deleteQuarantine(@PathVariable("id") Long id) {
        if (id == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id is null"));
        if (!memoryRepo.existsById(id)) return ResponseEntity.notFound().build();
        memoryRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    private static QuarantineItem toItem(TranslationMemory tm) {
        String content = (tm == null) ? "" : (tm.getCorrected() != null && !tm.getCorrected().isBlank() ? tm.getCorrected() : tm.getContent());
        String preview = (content == null) ? "" : content.replaceAll("\s+", " ").trim();
        if (preview.length() > 240) preview = preview.substring(0, 240) + "...";

        return new QuarantineItem(
                tm == null ? null : tm.getId(),
                tm == null ? null : tm.getSessionId(),
                tm == null ? null : tm.getSourceTag(),
                tm == null || tm.getStatus() == null ? null : tm.getStatus().name(),
                tm == null || tm.getCreatedAt() == null ? null : tm.getCreatedAt().toString(),
                tm == null || tm.getUpdatedAt() == null ? null : tm.getUpdatedAt().toString(),
                tm == null || tm.getLastUsedAt() == null ? null : tm.getLastUsedAt().toString(),
                preview
        );
    }
}
