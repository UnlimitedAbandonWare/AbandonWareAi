package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.lifecycle.DurableLifecycleReceiptStore;
import com.example.lms.storage.LocalFileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import com.example.lms.util.TokenClipper;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



// Token clipping helper for limiting attachment length

/**
 * 첨부 파일을 저장하고 메타데이터를 관리하는 서비스.
 *
 * 현재는 간단한 MVP 구현으로 인메모리 저장소를 사용하여 첨부 메타를 관리합니다.
 * 필요 시 JPA 저장소로 교체할 수 있습니다.
 */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final long DEFAULT_MAX_DOCUMENT_BYTES = 1_048_576L;
    static final long DEFAULT_RETENTION_TTL_MS = 86_400_000L;
    private final Object metadataMutationLock = new Object();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>> sessionIndex = new java.util.concurrent.ConcurrentHashMap<>();

    private final LocalFileStorageService storage;
    private final DurableLifecycleReceiptStore receiptStore;
    /** In-memory 저장소로 첨부 메타를 보관합니다. */
    private final Map<String, AttachmentDto> repo = new ConcurrentHashMap<>();
    private final Map<String, String> extractedTextById = new ConcurrentHashMap<>();
    private final Map<String, String> contentDigestById = new ConcurrentHashMap<>();
    private final Map<String, Long> retainedAtEpochMsById = new ConcurrentHashMap<>();
    private final Map<String, String> ownerHashById = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private Environment environment;

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        try {
            com.example.lms.search.TraceStore.put("attachment.suppressed." + safeStage, true);
            com.example.lms.search.TraceStore.put("attachment.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[AttachmentService] suppressed trace failed stage={} errorType={}",
                    safeStage, traceFailure.getClass().getSimpleName());
        }
    }

    /**
     * Service used to extract plain text from uploaded files.  Injected via
     * constructor to allow {@link #asDocuments(List)} to delegate content
     * extraction without performing manual bean lookup.  See
     * {@link com.example.lms.file.FileIngestionService} for supported formats.
     */
    private final com.example.lms.file.FileIngestionService fileIngestionService;

    public AttachmentService(
            LocalFileStorageService storage,
            com.example.lms.file.FileIngestionService fileIngestionService) {
        this(storage, fileIngestionService, DurableLifecycleReceiptStore.none());
    }

    @Autowired
    public AttachmentService(
            LocalFileStorageService storage,
            com.example.lms.file.FileIngestionService fileIngestionService,
            DurableLifecycleReceiptStore receiptStore) {
        this.storage = storage;
        this.fileIngestionService = Objects.requireNonNull(fileIngestionService, "fileIngestionService");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    }

    /**
     * 여러 MultipartFile을 저장하고 AttachmentDto 목록을 반환합니다.
     *
     * @param files 업로드할 파일 목록
     * @return 저장된 파일 메타 정보 목록
     */
    public List<AttachmentDto> saveAll(List<MultipartFile> files) {
        return saveAllInternal(files, null, null);
    }

    public List<AttachmentDto> saveAll(
            List<MultipartFile> files,
            AttachmentOwnerIdentity ownerIdentity) {
        return saveAllInternal(files, null, Objects.requireNonNull(ownerIdentity, "ownerIdentity"));
    }

    private List<AttachmentDto> saveAllInternal(
            List<MultipartFile> files,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity) {
        List<AttachmentDto> out = new ArrayList<>();
        if (files == null) return out;
        try {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                String id = UUID.randomUUID().toString();
                String trustedDigest = interactionEvidenceObservationEnabled() ? digestOf(f) : null;
                // LocalFileStorageService.save 의 시그니처는 (MultipartFile file, String subPath)
                // 루트 업로드 디렉터리 하위에 "chat" 폴더를 생성하여 파일을 저장합니다.
                String url = storage.save(f, "chat");
                AttachmentDto dto = new AttachmentDto(
                        id,
                        f.getOriginalFilename(),
                        f.getSize(),
                        f.getContentType(),
                        url
                );
                synchronized (metadataMutationLock) {
                    long retainedAtEpochMs = System.currentTimeMillis();
                    repo.put(id, dto);
                    if (trustedDigest != null) {
                        contentDigestById.put(id, trustedDigest);
                    }
                    retainedAtEpochMsById.put(id, retainedAtEpochMs);
                    if (ownerIdentity != null) {
                        ownerHashById.put(id, ownerIdentity.hash());
                    }
                    if (sessionId != null && !sessionId.isBlank()) {
                        linkToSessionLocked(sessionId, id);
                    }
                }
                out.add(dto);
                recordAttachmentLifecycle(
                        dto,
                        DurableLifecycleReceiptStore.State.CREATED,
                        DurableLifecycleReceiptStore.Reason.NONE);
            }
            return out;
        } catch (RuntimeException | Error failure) {
            rollbackSavedAttachments(out);
            throw failure;
        }
    }

    /** 특정 ID의 첨부 메타를 조회합니다. */
    public Optional<AttachmentDto> find(String id) {
        return Optional.ofNullable(repo.get(id));
    }

    public Optional<AttachmentDto> find(String id, AttachmentOwnerIdentity ownerIdentity) {
        if (id == null || !isOwnedBy(id, ownerIdentity)) {
            return Optional.empty();
        }
        return Optional.ofNullable(repo.get(id));
    }

    /**
     * 첨부 메타를 삭제한 뒤 메타데이터 락 밖에서 저장 파일 삭제를 시도합니다.
     * @param id 첨부 ID
     */
    public void delete(String id) {
        if (id == null || id.isBlank()) {
            com.example.lms.search.TraceStore.put("attachment.delete.denied", true);
            com.example.lms.search.TraceStore.put("attachment.delete.deniedReason", "missing_id");
            return;
        }
        MetadataDeletion deletion;
        synchronized (metadataMutationLock) {
            deletion = deleteMetadataLocked(id);
        }
        deleteStoredBytes(deletion.dto());
        traceDeletion(id, deletion);
    }

    private static void traceDeletion(String id, MetadataDeletion deletion) {
        if (deletion.dto() != null) {
            com.example.lms.search.TraceStore.put("attachment.delete.ok", true);
            com.example.lms.search.TraceStore.put(
                    "attachment.delete.sessionLinksRemoved", deletion.removedSessionLinks());
            log.debug("Attachment deleted: idHash={}", com.example.lms.trace.SafeRedactor.hash12(id));
        } else {
            com.example.lms.search.TraceStore.put("attachment.delete.notFound", true);
            com.example.lms.search.TraceStore.put("attachment.delete.idHash",
                    com.example.lms.trace.SafeRedactor.hashValue(id));
        }
    }

    public boolean deleteForSession(String id, String sessionId) {
        return deleteForSessionInternal(id, sessionId, null, false);
    }

    public boolean deleteForSession(
            String id,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity) {
        return deleteForSessionInternal(
                id, sessionId, Objects.requireNonNull(ownerIdentity, "ownerIdentity"), true);
    }

    private boolean deleteForSessionInternal(
            String id,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity,
            boolean requireOwner) {
        if (id == null || id.isBlank() || sessionId == null || sessionId.isBlank()) {
            com.example.lms.search.TraceStore.put("attachment.delete.denied", true);
            com.example.lms.search.TraceStore.put("attachment.delete.deniedReason", "missing_session_or_id");
            return false;
        }
        MetadataDeletion deletion;
        synchronized (metadataMutationLock) {
            if (!repo.containsKey(id)) {
                com.example.lms.search.TraceStore.put("attachment.delete.denied", true);
                com.example.lms.search.TraceStore.put("attachment.delete.deniedReason", "missing_attachment");
                return false;
            }
            java.util.List<String> owned = sessionIndex.getOrDefault(sessionId, java.util.List.of());
            if (!owned.contains(id)) {
                com.example.lms.search.TraceStore.put("attachment.delete.denied", true);
                com.example.lms.search.TraceStore.put("attachment.delete.deniedReason", "session_mismatch");
                return false;
            }
            if (requireOwner && !isOwnedBy(id, ownerIdentity)) {
                com.example.lms.search.TraceStore.put("attachment.delete.denied", true);
                com.example.lms.search.TraceStore.put("attachment.delete.deniedReason", "owner_mismatch");
                return false;
            }
            deletion = deleteMetadataLocked(id);
        }
        deleteStoredBytes(deletion.dto());
        traceDeletion(id, deletion);
        com.example.lms.search.TraceStore.put("attachment.delete.sessionScoped", true);
        return true;
    }

    public void cacheExtractedText(String id, String text) {
        if (id == null || id.isBlank() || text == null || text.isBlank()) {
            return;
        }
        int limit = 50_000;
        String bounded = text.length() <= limit ? text : text.substring(0, limit);
        synchronized (metadataMutationLock) {
            if (repo.containsKey(id)) {
                extractedTextById.put(id, bounded);
            }
        }
    }

    public java.util.List<com.example.lms.dto.AttachmentDto> saveAll(java.util.List<org.springframework.web.multipart.MultipartFile> files, String sessionId) {
        return saveAllInternal(files, sessionId, null);
    }

    public java.util.List<com.example.lms.dto.AttachmentDto> saveAll(
            java.util.List<org.springframework.web.multipart.MultipartFile> files,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity) {
        return saveAllInternal(
                files, sessionId, Objects.requireNonNull(ownerIdentity, "ownerIdentity"));
    }

    public java.util.List<com.example.lms.dto.AttachmentDto> findBySession(String sessionId) {
        return findBySessionInternal(sessionId, null, false);
    }

    public java.util.List<com.example.lms.dto.AttachmentDto> findBySession(
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity) {
        return findBySessionInternal(
                sessionId, Objects.requireNonNull(ownerIdentity, "ownerIdentity"), true);
    }

    private java.util.List<com.example.lms.dto.AttachmentDto> findBySessionInternal(
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity,
            boolean requireOwner) {
        if (sessionId == null || sessionId.isBlank()) return java.util.List.of();
        java.util.List<String> ids = sessionIndex.getOrDefault(sessionId, java.util.List.of());
        // ConcurrentHashMap does not provide a snapshot() method.  Make a shallow copy
        // to avoid concurrent modification issues while iterating.  Using a plain
        // HashMap preserves current entries at the point of invocation.
        java.util.Map<String, com.example.lms.dto.AttachmentDto> map = new java.util.HashMap<>(repo);
        java.util.List<com.example.lms.dto.AttachmentDto> out = new java.util.ArrayList<>();
        for (String id : ids) {
            if (requireOwner && !isOwnedBy(id, ownerIdentity)) {
                continue;
            }
            com.example.lms.dto.AttachmentDto dto = map.get(id);
            if (dto != null) out.add(dto);
        }
        return out;
    }

    /**
     * Returns at most {@code lookahead} live attachment identifiers without
     * copying the global attachment repository or materializing DTOs.
     */
    public java.util.List<String> findIdsBySession(String sessionId, int lookahead) {
        return findIdsBySessionInternal(sessionId, lookahead, null, false);
    }

    public java.util.List<String> findIdsBySession(
            String sessionId,
            int lookahead,
            AttachmentOwnerIdentity ownerIdentity) {
        return findIdsBySessionInternal(
                sessionId,
                lookahead,
                Objects.requireNonNull(ownerIdentity, "ownerIdentity"),
                true);
    }

    private java.util.List<String> findIdsBySessionInternal(
            String sessionId,
            int lookahead,
            AttachmentOwnerIdentity ownerIdentity,
            boolean requireOwner) {
        if (sessionId == null || sessionId.isBlank() || lookahead <= 0) {
            return java.util.List.of();
        }
        java.util.List<String> ids = sessionIndex.getOrDefault(sessionId, java.util.List.of());
        java.util.List<String> out = new java.util.ArrayList<>(Math.min(ids.size(), lookahead));
        for (String id : ids) {
            if (id != null
                    && repo.containsKey(id)
                    && (!requireOwner || isOwnedBy(id, ownerIdentity))) {
                out.add(id);
            }
            if (out.size() >= lookahead) break;
        }
        return out;
    }

    /**
     * Convert a list of attachment identifiers into a list of LangChain4j Documents.
     *
     * <p>This method loads each attachment from the in-memory repository, extracts
     * a textual representation via the {@link com.example.lms.file.FileIngestionService}
     * and wraps the result in a {@link dev.langchain4j.data.document.Document} with
     * metadata describing the attachment.  Attachments whose content cannot be
     * extracted will be skipped silently.  For large files the extracted text is
     * clipped to a maximum of 6,000 tokens (approximately) by splitting on
     * whitespace and rejoining the first 6,000 tokens.  This prevents oversized
     * attachments from overflowing the prompt context.
     *
     * @param ids the identifiers of attachments associated with the current request
     * @return a list of documents representing the uploaded attachments
     */
    public java.util.List<dev.langchain4j.data.document.Document> asDocuments(java.util.List<String> ids) {
        java.util.List<dev.langchain4j.data.document.Document> result = new java.util.ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            com.example.lms.search.TraceStore.put("attachment.localDocs.count", 0);
            return result;
        }
        for (String id : ids) {
            try {
                com.example.lms.dto.AttachmentDto dto = this.repo.get(id);
                if (dto == null) continue;
                String text = extractedTextById.get(id);
                // Load file content from storage.  The AttachmentDto.url field stores the
                // absolute file path returned by LocalFileStorageService.save().  Read
                // the bytes from disk and extract a plain text representation using
                // FileIngestionService.  Note: FileIngestionService returns null on
                // failure and logs any underlying exceptions.
                // Normalise and resolve the stored URL into an absolute file system path.
                // The AttachmentDto.url field returned by LocalFileStorageService.save()
                // begins with a '/' and points to a relative uploads directory (e.g.
                // '/uploads/chat/file.txt').  Attempt to read the file at the given
                // path; when that fails convert the web-style path into an absolute
                // path relative to the application working directory by trimming the
                // leading slash.  This fallback supports both Windows and Unix file
                // systems by replacing backslashes with forward slashes.
                byte[] bytes = null;
                if (text == null || text.isBlank()) {
                    java.nio.file.Path path = java.nio.file.Path.of(dto.url().replace('\\','/'));
                    String cleaned = dto.url().replace('\\','/').replaceFirst("^/+", "");
                    java.nio.file.Path resolved = java.nio.file.Path.of(cleaned).toAbsolutePath().normalize();
                    java.nio.file.Path readPath = null;
                    if (java.nio.file.Files.exists(path)) {
                        readPath = path;
                    } else if (java.nio.file.Files.exists(resolved)) {
                        readPath = resolved;
                        log.debug("Resolved attachment path: id={} urlHash={}",
                                id, com.example.lms.trace.SafeRedactor.hash12(dto.url()));
                    }
                    if (readPath == null) {
                        log.warn("Attachment not found: id={} urlHash={} existsOrig={} existsResolved={}",
                                id, com.example.lms.trace.SafeRedactor.hash12(dto.url()),
                                java.nio.file.Files.exists(path), java.nio.file.Files.exists(resolved));
                        continue;
                    }
                    long size = java.nio.file.Files.size(readPath);
                    long maxBytes = maxDocumentBytes();
                    if (size > maxBytes) {
                        com.example.lms.search.TraceStore.put("attachment.text.emptyReason",
                                com.example.lms.trace.SafeRedactor.traceLabelOrFallback("attachment_extraction_skipped_too_large", "unknown"));
                        com.example.lms.search.TraceStore.put("attachment.extraction.skippedReason", "too_large");
                        com.example.lms.search.TraceStore.put("attachment.extraction.maxBytes", maxBytes);
                        log.warn("Attachment extraction skipped: id={} urlHash={} size={} maxBytes={}",
                                id, com.example.lms.trace.SafeRedactor.hash12(dto.url()), size, maxBytes);
                        continue;
                    }
                    bytes = java.nio.file.Files.readAllBytes(readPath);
                    // Extract plain text from the file using the injected FileIngestionService.
                    try {
                        text = fileIngestionService.extractText(dto.name(), dto.contentType(), bytes);
                    } catch (Exception ignore) {
                        traceSuppressed("attachment.extractText", ignore);
                    }
                }
                // MERGE_HOOK:PROJ_AGENT::utf8_fallback_guard
                // Fallback: if extraction returns null or blank, attempt to interpret the
                // bytes as UTF-8 text.  단, 이미지/오디오/비디오/압축 등 바이너리 파일은
                // UTF-8 폴백을 시도하지 않는다.
                String ct = dto.contentType() == null ? "" : dto.contentType().toLowerCase(java.util.Locale.ROOT);
                String name = dto.name() == null ? "" : dto.name().toLowerCase(java.util.Locale.ROOT);

                boolean likelyBinary = ct.startsWith("image/")
                        || ct.startsWith("audio/")
                        || ct.startsWith("video/")
                        || name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".exe")
                        || name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".png")
                        || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif");

                boolean utf8FallbackAttempted = false;
                String utf8FallbackEmptyReason = null;
                if (text == null || text.isBlank()) {
                    if (likelyBinary) {
                        log.debug("Skipping UTF-8 fallback for binary-like attachment id={} nameHash={} contentType={}",
                                id, com.example.lms.trace.SafeRedactor.hash12(dto.name()), dto.contentType());
                        continue;
                    }
                    try {
                        utf8FallbackAttempted = true;
                        text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ignore) {
                        traceSuppressed("attachment.utf8Fallback", ignore);
                        utf8FallbackEmptyReason = "utf8_fallback_failed";
                        text = "";
                    }
                }
                if (text == null || text.isBlank()) {
                    if (utf8FallbackAttempted) {
                        String reason = utf8FallbackEmptyReason == null ? "utf8_fallback_blank" : utf8FallbackEmptyReason;
                        com.example.lms.search.TraceStore.put("attachment.text.emptyReason",
                                com.example.lms.trace.SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                        com.example.lms.search.TraceStore.put("attachment.extraction.skippedReason", reason);
                    }
                    continue;
                }
                // Clip the text to a maximum of 6,000 tokens using the TokenClipper utility.
                // This helper splits on whitespace and truncates to the requested number
                // of tokens, providing coarse length control without relying on heavy
                // tokenisation libraries.  When the extracted text contains fewer
                // tokens than the limit it is returned unchanged.
                text = TokenClipper.clip(text, 6000);
                // Prepare document metadata.  The metadata maps common fields so that
                // downstream handlers can identify the source and attachment details.
                java.util.Map<String,Object> meta = new java.util.HashMap<>();
                meta.put("source", "attachment");
                meta.put("attachmentId", id);
                meta.put("contentType", dto.contentType());
                meta.put("name", dto.name());
                meta.put("attachmentType", attachmentType(dto.name(), dto.contentType()));
                // LC4J 1.0.1: Document is abstract → use factory method
                dev.langchain4j.data.document.Document doc =
                        dev.langchain4j.data.document.Document.from(
                                text, new dev.langchain4j.data.document.Metadata(meta));
                result.add(doc);
            } catch (Exception ignore) {
                traceSuppressed("attachment.asDocuments.item", ignore);
            }
        }
        com.example.lms.search.TraceStore.put("attachment.localDocs.count", result.size());
        return result;
    }

    public java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSession(
            java.util.List<String> ids,
            String sessionId) {
        return asDocumentsForSessionInternal(ids, sessionId, null, false);
    }

    public java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSession(
            java.util.List<String> ids,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity) {
        return asDocumentsForSessionInternal(
                ids,
                sessionId,
                Objects.requireNonNull(ownerIdentity, "ownerIdentity"),
                true);
    }

    private java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSessionInternal(
            java.util.List<String> ids,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity,
            boolean requireOwner) {
        if (ids == null || ids.isEmpty()) {
            return asDocuments(ids);
        }
        if (sessionId == null || sessionId.isBlank()) {
            com.example.lms.search.TraceStore.put("attachment.sessionFilter.applied", false);
            com.example.lms.search.TraceStore.put("attachment.sessionFilter.reason", "missing_session");
            return requireOwner ? java.util.List.of() : asDocuments(ids);
        }
        java.util.Set<String> allowed = new java.util.HashSet<>(sessionIndex.getOrDefault(sessionId, java.util.List.of()));
        if (allowed.isEmpty()) {
            com.example.lms.search.TraceStore.put("attachment.sessionFilter.applied", true);
            com.example.lms.search.TraceStore.put("attachment.sessionFilter.allowedCount", 0);
            com.example.lms.search.TraceStore.put("attachment.localDocs.count", 0);
            return java.util.List.of();
        }
        java.util.List<String> filtered = ids.stream()
                .filter(id -> id != null
                        && allowed.contains(id)
                        && (!requireOwner || isOwnedBy(id, ownerIdentity)))
                .toList();
        com.example.lms.search.TraceStore.put("attachment.sessionFilter.applied", true);
        com.example.lms.search.TraceStore.put("attachment.sessionFilter.allowedCount", filtered.size());
        com.example.lms.search.TraceStore.put("attachment.sessionFilter.blockedCount", ids.size() - filtered.size());
        return asDocuments(filtered);
    }

    /**
     * Produces only bounded, server-verified interaction facts before prompt work.
     * Client text and metadata never become policy facts by themselves.
     */
    void observeInteractionEvidence(
            java.util.List<String> ids,
            String sessionId,
            com.example.lms.service.guard.GuardContext context) {
        if (!interactionEvidenceObservationEnabled() || context == null || ids == null || ids.isEmpty()) {
            return;
        }
        String requestedSession = sessionId == null ? "" : sessionId.trim();
        for (String id : ids.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            AttachmentDto dto = repo.get(id);
            if (dto == null) {
                continue;
            }
            try {
                boolean linkedToAnotherSession = !requestedSession.isBlank()
                        && isLinkedToDifferentSession(id, requestedSession);
                if (linkedToAnotherSession) {
                    context.recordInteractionPolicyFact(
                            interactionFact(
                                    com.example.lms.guard.InteractionEvidencePolicy.ManipulationKind.UNAUTHORIZED_ACCESS,
                                    com.example.lms.guard.InteractionEvidencePolicy.ProofKind.AUTHORIZATION_DENIED,
                                    com.example.lms.guard.InteractionEvidencePolicy.DetectorRule.SESSION_AUTHORIZATION_V1),
                            id);
                    context.recordInteractionPolicyFact(
                            interactionFact(
                                    com.example.lms.guard.InteractionEvidencePolicy.ManipulationKind.EVIDENCE_TAMPERING,
                                    com.example.lms.guard.InteractionEvidencePolicy.ProofKind.PROVENANCE_MISMATCH,
                                    com.example.lms.guard.InteractionEvidencePolicy.DetectorRule.EVIDENCE_PROVENANCE_V1),
                            id);
                    continue;
                }

                java.nio.file.Path path = resolveExistingAttachmentPath(dto);
                if (path != null) {
                    String actualDigest = digestOf(path);
                    String expectedDigest = contentDigestById.get(id);
                    if (expectedDigest != null && actualDigest != null
                            && !java.security.MessageDigest.isEqual(
                            expectedDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                        context.recordInteractionPolicyFact(
                                interactionFact(
                                        com.example.lms.guard.InteractionEvidencePolicy.ManipulationKind.EVIDENCE_TAMPERING,
                                        com.example.lms.guard.InteractionEvidencePolicy.ProofKind.DIGEST_MISMATCH,
                                        com.example.lms.guard.InteractionEvidencePolicy.DetectorRule.EVIDENCE_DIGEST_V1),
                                id);
                    }
                }
            } catch (RuntimeException inspectionFailure) {
                traceSuppressed("attachment.interactionIntegrity", inspectionFailure);
            }
        }
    }

    private boolean interactionEvidenceObservationEnabled() {
        String configuredMode = environment == null
                ? null
                : environment.getProperty("interaction.evidence-neutral.mode");
        return com.example.lms.guard.InteractionEvidencePolicy.FeatureMode.parse(configuredMode)
                != com.example.lms.guard.InteractionEvidencePolicy.FeatureMode.OFF;
    }

    private String digestOf(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return null;
            }
            try (java.io.InputStream in = file.getInputStream()) {
                return org.apache.commons.codec.digest.DigestUtils.sha256Hex(in);
            }
        } catch (Exception failure) {
            traceSuppressed("attachment.digest.capture", failure);
            return null;
        }
    }

    private static com.example.lms.guard.InteractionEvidencePolicy.ManipulationFact interactionFact(
            com.example.lms.guard.InteractionEvidencePolicy.ManipulationKind kind,
            com.example.lms.guard.InteractionEvidencePolicy.ProofKind proof,
            com.example.lms.guard.InteractionEvidencePolicy.DetectorRule rule) {
        return new com.example.lms.guard.InteractionEvidencePolicy.ManipulationFact(
                kind,
                proof,
                rule,
                com.example.lms.guard.InteractionEvidencePolicy.SourceSurface.RETRIEVED_EVIDENCE);
    }

    private String digestOf(java.nio.file.Path path) {
        try {
            if (path == null || !java.nio.file.Files.isRegularFile(path)) {
                return null;
            }
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                return org.apache.commons.codec.digest.DigestUtils.sha256Hex(in);
            }
        } catch (Exception failure) {
            traceSuppressed("attachment.digest.verify", failure);
            return null;
        }
    }

    private static java.nio.file.Path resolveExistingAttachmentPath(AttachmentDto dto) {
        if (dto == null || dto.url() == null || dto.url().isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path direct = java.nio.file.Path.of(dto.url().replace('\\', '/'));
            if (java.nio.file.Files.isRegularFile(direct)) {
                return direct;
            }
            String cleaned = dto.url().replace('\\', '/').replaceFirst("^/+", "");
            java.nio.file.Path resolved = java.nio.file.Path.of(cleaned).toAbsolutePath().normalize();
            return java.nio.file.Files.isRegularFile(resolved) ? resolved : null;
        } catch (RuntimeException invalidPath) {
            traceSuppressed("attachment.path.resolve", invalidPath);
            return null;
        }
    }

    private long maxDocumentBytes() {
        long value = longProperty("attachments.documents.maxBytes", -1L);
        if (value <= 0) {
            value = longProperty("attachments.inline.maxDocBytes", DEFAULT_MAX_DOCUMENT_BYTES);
        }
        return Math.max(1L, value);
    }

    int evictExpired(long nowEpochMs) {
        return evictExpiredWithOutcome(nowEpochMs).metadataEvictedCount();
    }

    private EvictionOutcome evictExpiredWithOutcome(long nowEpochMs) {
        long ttlMs = retentionTtlMs();
        int evicted = 0;
        java.util.List<AttachmentDto> deletedDtos = new java.util.ArrayList<>();
        synchronized (metadataMutationLock) {
            java.util.List<java.util.Map.Entry<String, Long>> retained =
                    new java.util.ArrayList<>(retainedAtEpochMsById.entrySet());
            for (java.util.Map.Entry<String, Long> entry : retained) {
                Long retainedAtEpochMs = entry.getValue();
                if (retainedAtEpochMs != null
                        && isExpired(nowEpochMs, retainedAtEpochMs, ttlMs)) {
                    MetadataDeletion deletion = deleteMetadataLocked(entry.getKey());
                    if (deletion.dto() != null) {
                        deletedDtos.add(deletion.dto());
                    }
                    evicted++;
                }
            }
        }
        int physicalDeleted = 0;
        int physicalDeleteFailed = 0;
        for (AttachmentDto dto : deletedDtos) {
            if (deleteStoredBytes(dto)) {
                physicalDeleted++;
            } else {
                physicalDeleteFailed++;
            }
        }
        com.example.lms.search.TraceStore.put(
                "attachment.retention.metadataEvictedCount", evicted);
        com.example.lms.search.TraceStore.put(
                "attachment.retention.physicalDeletedCount", physicalDeleted);
        com.example.lms.search.TraceStore.put(
                "attachment.retention.physicalDeleteFailedCount", physicalDeleteFailed);
        return new EvictionOutcome(evicted, physicalDeleted, physicalDeleteFailed);
    }

    @Scheduled(fixedDelayString = "${attachments.retention.cleanup-interval-ms:300000}")
    public void evictExpiredAttachments() {
        EvictionOutcome outcome = evictExpiredWithOutcome(System.currentTimeMillis());
        if (outcome.metadataEvictedCount() > 0) {
            log.debug(
                    "[AttachmentService] expired metadata evicted count={} physical deleted count={} physical delete failed count={}",
                    outcome.metadataEvictedCount(),
                    outcome.physicalDeletedCount(),
                    outcome.physicalDeleteFailedCount());
        }
    }

    private long retentionTtlMs() {
        long configured = longProperty(
                "attachments.retention.ttl-ms", DEFAULT_RETENTION_TTL_MS);
        return configured > 0L ? configured : DEFAULT_RETENTION_TTL_MS;
    }

    private static boolean isExpired(long nowEpochMs, long retainedAtEpochMs, long ttlMs) {
        if (ttlMs <= 0L || nowEpochMs < retainedAtEpochMs) {
            return false;
        }
        try {
            return Math.subtractExact(nowEpochMs, retainedAtEpochMs) >= ttlMs;
        } catch (ArithmeticException positiveAgeOverflow) {
            traceSuppressed("attachment.retention.ageOverflow", positiveAgeOverflow);
            return true;
        }
    }

    private long longProperty(String key, long fallback) {
        try {
            if (environment != null) {
                String raw = environment.getProperty(key);
                if (raw != null && !raw.isBlank()) {
                    return Long.parseLong(raw.trim());
                }
            }
        } catch (Exception ignore) {
            traceSuppressed("attachment.propertyLong", ignore);
        }
        return fallback;
    }

    private static String attachmentType(String name, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        String fn = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (fn.endsWith(".zip")
                || ct.equals("application/zip")
                || ct.equals("application/x-zip-compressed")
                || (ct.equals("application/octet-stream") && fn.endsWith(".zip"))) {
            return "archive";
        }
        if (fn.endsWith(".pdf") || ct.equals("application/pdf")) {
            return "pdf";
        }
        if (fn.endsWith(".docx") || fn.endsWith(".pptx") || fn.endsWith(".xlsx") || fn.endsWith(".hwpx")
                || ct.contains("officedocument") || ct.contains("hwp")) {
            return "office";
        }
        if (ct.startsWith("text/")) {
            return "text";
        }
        return "attachment";
    }

    /**
     * Associate a list of existing attachment identifiers with the given session.  When
     * files are uploaded prior to session creation the attachments will not be
     * discoverable via {@link #findBySession(String)}.  This helper updates the
     * in-memory session index to include the provided IDs.  Unknown IDs are
     * ignored.
     *
     * @param sessionId the session identifier (must not be blank)
     * @param ids       identifiers of attachments to map to the session
     */
    public void attachToSession(String sessionId, java.util.List<String> ids) {
        if (sessionId == null || sessionId.isBlank() || ids == null || ids.isEmpty()) {
            return;
        }
        synchronized (metadataMutationLock) {
            for (String id : ids) {
                linkToSessionLocked(sessionId, id);
            }
        }
    }

    public boolean attachToSession(
            String sessionId,
            java.util.List<String> ids,
            AttachmentOwnerIdentity ownerIdentity) {
        if (sessionId == null || sessionId.isBlank() || ids == null || ids.isEmpty()) {
            return false;
        }
        Objects.requireNonNull(ownerIdentity, "ownerIdentity");
        synchronized (metadataMutationLock) {
            for (String id : ids) {
                if (id == null
                        || id.isBlank()
                        || !repo.containsKey(id)
                        || !isOwnedBy(id, ownerIdentity)
                        || isLinkedToDifferentSession(id, sessionId)) {
                    return false;
                }
            }
            for (String id : ids) {
                linkToSessionLocked(sessionId, id);
            }
            return true;
        }
    }

    private void linkToSessionLocked(String sessionId, String id) {
        if (id == null || id.isBlank()
                || !repo.containsKey(id)
                || isLinkedToDifferentSession(id, sessionId)) {
            return;
        }
        java.util.List<String> existing = sessionIndex.computeIfAbsent(sessionId,
                k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (!existing.contains(id)) {
            existing.add(id);
        }
    }

    private MetadataDeletion deleteMetadataLocked(String id) {
        AttachmentDto existing = repo.get(id);
        if (existing != null) {
            recordAttachmentLifecycle(
                    existing,
                    DurableLifecycleReceiptStore.State.DELETE_REQUESTED,
                    DurableLifecycleReceiptStore.Reason.NONE);
        }
        AttachmentDto dto = repo.remove(id);
        extractedTextById.remove(id);
        contentDigestById.remove(id);
        retainedAtEpochMsById.remove(id);
        ownerHashById.remove(id);
        int removedSessionLinks = removeFromSessionIndex(id);
        return new MetadataDeletion(dto, removedSessionLinks);
    }

    private boolean deleteStoredBytes(AttachmentDto dto) {
        if (dto == null) {
            return false;
        }
        boolean deleted = false;
        try {
            deleted = storage != null && storage.delete(dto.url());
        } catch (RuntimeException failure) {
            traceSuppressed("attachment.delete.physical", failure);
        }
        com.example.lms.search.TraceStore.put("attachment.delete.physicalDeleted", deleted);
        com.example.lms.search.TraceStore.put(
                "attachment.delete.physicalDeleteReason",
                deleted ? null : "storage_delete_failed");
        recordAttachmentLifecycle(
                dto,
                deleted
                        ? DurableLifecycleReceiptStore.State.DELETED
                        : DurableLifecycleReceiptStore.State.DELETE_FAILED,
                deleted
                        ? DurableLifecycleReceiptStore.Reason.NONE
                        : DurableLifecycleReceiptStore.Reason.STORAGE_DELETE_FAILED);
        return deleted;
    }

    private void recordAttachmentLifecycle(
            AttachmentDto dto,
            DurableLifecycleReceiptStore.State state,
            DurableLifecycleReceiptStore.Reason reason) {
        if (dto == null || dto.id() == null) {
            return;
        }
        receiptStore.record(new DurableLifecycleReceiptStore.Receipt(
                DurableLifecycleReceiptStore.SCHEMA_VERSION,
                DurableLifecycleReceiptStore.Lifecycle.ATTACHMENT,
                DurableLifecycleReceiptStore.hash("attachment-id", dto.id()),
                DurableLifecycleReceiptStore.hash("attachment-path", String.valueOf(dto.url())),
                state,
                System.currentTimeMillis(),
                reason));
    }

    private void rollbackSavedAttachments(List<AttachmentDto> saved) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        List<AttachmentDto> physicalDeletes = new ArrayList<>(saved.size());
        synchronized (metadataMutationLock) {
            for (int index = saved.size() - 1; index >= 0; index--) {
                AttachmentDto dto = saved.get(index);
                if (dto == null || dto.id() == null) {
                    continue;
                }
                MetadataDeletion deletion = deleteMetadataLocked(dto.id());
                if (deletion.dto() != null) {
                    physicalDeletes.add(deletion.dto());
                }
            }
        }
        for (AttachmentDto dto : physicalDeletes) {
            deleteStoredBytes(dto);
        }
        com.example.lms.search.TraceStore.put("attachment.save.rollbackCount", physicalDeletes.size());
    }

    private int removeFromSessionIndex(String id) {
        int removed = 0;
        for (java.util.Map.Entry<String, java.util.List<String>> entry : sessionIndex.entrySet()) {
            java.util.List<String> ids = entry.getValue();
            if (ids == null) {
                sessionIndex.remove(entry.getKey(), null);
                continue;
            }
            if (ids.remove(id)) {
                removed++;
            }
            if (ids.isEmpty()) {
                sessionIndex.remove(entry.getKey(), ids);
            }
        }
        return removed;
    }

    private boolean isLinkedToDifferentSession(String id, String sessionId) {
        if (id == null || id.isBlank() || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        for (java.util.Map.Entry<String, java.util.List<String>> entry : sessionIndex.entrySet()) {
            if (!java.util.Objects.equals(sessionId, entry.getKey())
                    && entry.getValue() != null
                    && entry.getValue().contains(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOwnedBy(String id, AttachmentOwnerIdentity ownerIdentity) {
        if (id == null || ownerIdentity == null) {
            return false;
        }
        String expected = ownerHashById.get(id);
        return expected != null && expected.equals(ownerIdentity.hash());
    }

    private record MetadataDeletion(AttachmentDto dto, int removedSessionLinks) {
    }

    private record EvictionOutcome(
            int metadataEvictedCount,
            int physicalDeletedCount,
            int physicalDeleteFailedCount) {
    }

}
