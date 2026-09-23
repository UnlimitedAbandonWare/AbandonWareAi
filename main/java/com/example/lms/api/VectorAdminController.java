package com.example.lms.api;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.repository.VectorQuarantineDlqRepository;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.VectorBackendHealthService;
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.entity.VectorQuarantineDlq;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/vector")
@RequiredArgsConstructor
public class VectorAdminController {

    private static final System.Logger LOG = System.getLogger(VectorAdminController.class.getName());

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
        String activeGlobalSid = vectorSidService.resolveActiveSid(LangChainRAGService.GLOBAL_SID);
        String quarantineSid = vectorSidService.quarantineSid();
        out.put("sid", vectorSidService.snapshot());
        out.put("activeGlobalSidPresent", activeGlobalSid != null && !activeGlobalSid.isBlank());
        out.put("activeGlobalSidHash", SafeRedactor.hashValue(activeGlobalSid));
        out.put("quarantineSidHash", SafeRedactor.hashValue(quarantineSid));
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
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("status", status == null ? null : status.name());
        filters.put("sidBasePresent", sidBase != null && !sidBase.isBlank());
        filters.put("sidBaseHash", SafeRedactor.hashValue(sidBase));
        out.put("filters", filters);
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("items", p.getContent().stream().map(VectorAdminController::toDlqSummary).toList());
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
            Map<String, Object> out = idMeta(id);
            out.put("enabled", true);
            out.put("error", "not_found");
            return ResponseEntity.status(404).body(out);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("id", row.getId());
        out.put("status", row.getStatus() == null ? null : row.getStatus().name());
        out.put("attemptCount", row.getAttemptCount());
        out.put("nextAttemptAt", row.getNextAttemptAt());
        out.put("lastAttemptAt", row.getLastAttemptAt());
        out.put("lastErrorPresent", row.getLastError() != null && !row.getLastError().isBlank());
        out.put("lastErrorHash", SafeRedactor.hashValue(row.getLastError()));
        out.put("lastErrorLength", row.getLastError() == null ? 0 : row.getLastError().length());
        out.put("originalSidHash", SafeRedactor.hashValue(row.getOriginalSid()));
        out.put("originalSidBaseHash", SafeRedactor.hashValue(row.getOriginalSidBase()));
        out.put("quarantineReason", SafeRedactor.traceLabelOrFallback(row.getQuarantineReason(), "unknown"));
        out.put("quarantineVectorIdHash", SafeRedactor.hashValue(row.getQuarantineVectorId()));
        out.put("originalVectorIdHash", SafeRedactor.hashValue(row.getOriginalVectorId()));
        out.put("lockedByHash", SafeRedactor.hashValue(row.getLockedBy()));
        out.put("lockedAt", row.getLockedAt());
        out.put("createdAt", row.getCreatedAt());
        out.put("updatedAt", row.getUpdatedAt());
        out.put("metaJsonHash", SafeRedactor.hashValue(row.getMetaJson()));
        out.put("metaJsonLength", row.getMetaJson() == null ? 0 : row.getMetaJson().length());

        if (includePayload) {
            out.put("payloadPresent", row.getPayload() != null && !row.getPayload().isBlank());
            out.put("payloadHash", SafeRedactor.hashValue(row.getPayload()));
            out.put("payloadLength", row.getPayload() == null ? 0 : row.getPayload().length());
        }

        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> toDlqSummary(VectorQuarantineDlqRepository.DlqRecordSummary row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) {
            return out;
        }
        out.put("id", row.getId());
        out.put("status", row.getStatus() == null ? null : row.getStatus().name());
        out.put("attemptCount", row.getAttemptCount());
        out.put("nextAttemptAt", row.getNextAttemptAt());
        out.put("lastAttemptAt", row.getLastAttemptAt());
        out.put("lastErrorPresent", row.getLastError() != null && !row.getLastError().isBlank());
        out.put("lastErrorHash", SafeRedactor.hashValue(row.getLastError()));
        out.put("lastErrorLength", row.getLastError() == null ? 0 : row.getLastError().length());
        out.put("originalSidHash", SafeRedactor.hashValue(row.getOriginalSid()));
        out.put("originalSidBaseHash", SafeRedactor.hashValue(row.getOriginalSidBase()));
        out.put("quarantineReason", SafeRedactor.traceLabelOrFallback(row.getQuarantineReason(), "unknown"));
        out.put("quarantineVectorIdHash", SafeRedactor.hashValue(row.getQuarantineVectorId()));
        out.put("originalVectorIdHash", SafeRedactor.hashValue(row.getOriginalVectorId()));
        out.put("lockedByHash", SafeRedactor.hashValue(row.getLockedBy()));
        out.put("lockedAt", row.getLockedAt());
        out.put("createdAt", row.getCreatedAt());
        out.put("updatedAt", row.getUpdatedAt());
        return out;
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
                "logicalSidHash", hashOrEmpty(LangChainRAGService.GLOBAL_SID),
                "prevActiveSidPresent", prev != null && !prev.isBlank(),
                "prevActiveSidHash", hashOrEmpty(prev),
                "nextActiveSidPresent", next != null && !next.isBlank(),
                "nextActiveSidHash", hashOrEmpty(next)
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
            String sessionHash,
            String sourceTag,
            String status,
            String createdAt,
            String updatedAt,
            String lastUsedAt,
            String previewHash,
            int previewLength
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

        String st = (req == null || req.status() == null)
                ? ""
                : req.status().trim().toUpperCase(Locale.ROOT);
        if (st.isBlank()) st = "ACTIVE";

        TranslationMemory.MemoryStatus ns;
        try {
            ns = TranslationMemory.MemoryStatus.valueOf(st);
        } catch (Exception e) {
            traceSuppressed("vector.quarantine.status", e);
            ns = TranslationMemory.MemoryStatus.ACTIVE;
        }

        tm.setStatus(ns);
        memoryRepo.save(tm);

        return ResponseEntity.ok(quarantineAck(id, Map.of("status", ns.name())));
    }

    private static void traceSuppressed(String stage, Exception failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Vector admin fallback stage={0} errorType={1}",
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }

    @DeleteMapping("/quarantine/{id}")
    public ResponseEntity<Map<String, Object>> deleteQuarantine(@PathVariable("id") Long id) {
        if (id == null) return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "id is null"));
        if (!memoryRepo.existsById(id)) return ResponseEntity.notFound().build();
        memoryRepo.deleteById(id);
        return ResponseEntity.ok(quarantineAck(id, Map.of()));
    }

    private static Map<String, Object> quarantineAck(Long id, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(idMeta(id));
        if (extra != null) {
            out.putAll(extra);
        }
        return out;
    }

    private static Map<String, Object> idMeta(Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idHash", SafeRedactor.hashValue(String.valueOf(id)));
        out.put("idLength", String.valueOf(id).length());
        return out;
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static QuarantineItem toItem(TranslationMemory tm) {
        String content = (tm == null) ? "" : (tm.getCorrected() != null && !tm.getCorrected().isBlank() ? tm.getCorrected() : tm.getContent());
        String preview = (content == null) ? "" : content.replaceAll("\s+", " ").trim();
        if (preview.length() > 240) preview = preview.substring(0, 240) + "...";

        return new QuarantineItem(
                tm == null ? null : tm.getId(),
                SafeRedactor.hashValue(tm == null ? null : tm.getSessionId()),
                safeSourceTag(tm == null ? null : tm.getSourceTag()),
                tm == null || tm.getStatus() == null ? null : tm.getStatus().name(),
                tm == null || tm.getCreatedAt() == null ? null : tm.getCreatedAt().toString(),
                tm == null || tm.getUpdatedAt() == null ? null : tm.getUpdatedAt().toString(),
                tm == null || tm.getLastUsedAt() == null ? null : tm.getLastUsedAt().toString(),
                SafeRedactor.hashValue(preview),
                preview.length()
        );
    }

    private static String safeSourceTag(String sourceTag) {
        if (sourceTag == null || sourceTag.isBlank()) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(sourceTag, "unknown");
    }
}
