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
 * 소유권이 있는 첨부는 관계형 정본을 사용하며 인메모리 맵은 요청용 캐시입니다.
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

    @Autowired
    private AttachmentSourceStore durableStore;
    private final Map<String, AttachmentSourceStore.Snapshot> durableSnapshots = new ConcurrentHashMap<>();


    /** Revalidate the exact server snapshot without reparsing, changing selection or granting collection. */
    public boolean contextSourcesCurrent(com.example.lms.ensemble.PreparedContextPacket packet,
            AttachmentOwnerIdentity owner,String sessionId){
        if(packet==null)return false;
        var checked=new HashSet<String>();
        for(var span:packet.spans()){
            String id=com.example.lms.service.rag.graph.GeneralGraphSourceAuthority.sourceAttachmentId(span.sourceId());
            if(id==null)continue;
            if(owner==null||sessionId==null||durableStore==null)return false;
            var row=durableStore.find(id).orElse(null);
            if(row==null||!owner.hash().equals(row.ownerNamespace())||!sessionId.equals(row.sessionId())
                    ||row.sourceRevision()!=span.sourceRevision()||row.unitsJson()==null)return false;
            if(checked.add(id)){
                if(row.dto().size()<0||row.dto().size()>25*1048576L)return false;
                var path=storage.resolveStoredPath(row.dto().url());if(path.isEmpty())return false;
                try(var input=java.nio.file.Files.newInputStream(path.get())){
                    byte[] bytes=input.readNBytes(Math.toIntExact(row.dto().size())+1);
                    if(bytes.length!=row.dto().size()||!row.contentSha256().equals(
                            org.apache.commons.codec.digest.DigestUtils.sha256Hex(bytes)))return false;
                }catch(java.io.IOException unavailable){return false;}
            }
            try{
                var units=new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.unitsJson());
                boolean found=false;
                for(var unit:units){
                    String text=unit.path("text").asText("");
                    if(span.locator().equals(unit.path("locator").asText())
                            &&span.start()>=0&&span.end()<=text.length()
                            &&span.excerpt().equals(text.substring(span.start(),span.end()))){found=true;break;}
                }
                if(!found)return false;
            }catch(Exception invalid){return false;}
        }
        return true;
    }

    private AttachmentDto currentDto(String id) {
        if (id == null) return null;
        synchronized (metadataMutationLock) {
            if (durableStore == null) return repo.get(id); // constructor-only fixtures
            var persisted = durableStore.find(id);
            if (persisted.isPresent()) {
                cacheSnapshot(persisted.get());
            } else if (ownerHashById.containsKey(id) || durableSnapshots.containsKey(id)) {
                repo.remove(id);extractedTextById.remove(id);contentDigestById.remove(id);
                retainedAtEpochMsById.remove(id);ownerHashById.remove(id);durableSnapshots.remove(id);removeFromSessionIndex(id);
            }
            return repo.get(id);
        }
    }

    private void cacheSnapshot(AttachmentSourceStore.Snapshot snapshot) {
        String id=snapshot.dto().id();
        repo.compute(id,(key,old)->snapshot.dto().equals(old)?old:snapshot.dto());
        ownerHashById.put(id,snapshot.ownerNamespace());contentDigestById.put(id,snapshot.contentSha256());
        retainedAtEpochMsById.put(id,snapshot.retainedAt());durableSnapshots.put(id,snapshot);
        removeFromSessionIndex(id);
        if(snapshot.sessionId()!=null)sessionIndex.computeIfAbsent(snapshot.sessionId(),key->new java.util.concurrent.CopyOnWriteArrayList<>()).add(id);
    }

    private void restoreSession(String sessionId) {
        if(durableStore==null||sessionId==null)return;
        synchronized(metadataMutationLock){
            for(String id:List.copyOf(sessionIndex.getOrDefault(sessionId,List.of())))currentDto(id);
            for(var snapshot:durableStore.forSession(sessionId))cacheSnapshot(snapshot);
        }
    }

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
                String trustedDigest = digestOf(f);
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
                out.add(dto);
                synchronized (metadataMutationLock) {
                    long retainedAtEpochMs = System.currentTimeMillis();
                    if(durableStore!=null&&ownerIdentity!=null)
                        durableStore.create(dto,ownerIdentity.hash(),sessionId,trustedDigest,retainedAtEpochMs,
                            Math.addExact(retainedAtEpochMs,retentionTtlMs()));
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
        return Optional.ofNullable(currentDto(id));
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
            currentDto(id);
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
            currentDto(id);
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
        restoreSession(sessionId);
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
        restoreSession(sessionId);
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
        return asDocuments(ids, null);
    }

    private java.util.List<dev.langchain4j.data.document.Document> asDocuments(
            java.util.List<String> ids, String question) {
        java.util.List<dev.langchain4j.data.document.Document> result = new java.util.ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            com.example.lms.search.TraceStore.put("attachment.localDocs.count", 0);
            return result;
        }
        for (String id : ids) {
            try {
                com.example.lms.dto.AttachmentDto dto = currentDto(id);
                if (dto == null) continue;
                if (fileIngestionService.supportsStructuredDocument(dto.name(), dto.contentType())) {
                    result.addAll(structuredDocuments(dto, 16_000 / ids.size(), question));
                    continue;
                }
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
                    java.nio.file.Path readPath = resolveExistingAttachmentPath(dto);
                    if (readPath != null) {
                        // The configured storage root owns locator resolution.
                    } else if (durableStore == null && java.nio.file.Files.exists(path)) {
                        readPath = path;
                    } else if (durableStore == null && java.nio.file.Files.exists(resolved)) {
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

    private List<dev.langchain4j.data.document.Document> structuredDocuments(
            AttachmentDto dto, int charBudget, String question)
            throws java.io.IOException {
        java.nio.file.Path path = resolveExistingAttachmentPath(dto);
        if (path == null) return List.of();
        long maxBytes = maxDocumentBytes();
        if (java.nio.file.Files.size(path) > maxBytes) {
            com.example.lms.search.TraceStore.put("attachment.text.emptyReason",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback("attachment_extraction_skipped_too_large", "unknown"));
            com.example.lms.search.TraceStore.put("attachment.extraction.skippedReason", "too_large");
            com.example.lms.search.TraceStore.put("attachment.extraction.maxBytes", maxBytes);
            return List.of();
        }
        byte[] bytes;
        try (java.io.InputStream input = java.nio.file.Files.newInputStream(path)) {
            bytes = input.readNBytes(Math.toIntExact(Math.min(maxBytes, 25 * 1_048_576L)) + 1);
        }
        if (bytes.length > maxBytes) return List.of();
        String digest = org.apache.commons.codec.digest.DigestUtils.sha256Hex(bytes);
        var sourceSnapshot=durableSnapshots.get(dto.id());
        String expected = contentDigestById.get(dto.id());
        if (expected != null && !expected.equals(digest)) {
            com.example.lms.search.TraceStore.put("attachment.extraction.skippedReason", "content_digest_mismatch");
            return List.of();
        }
        var parsed = fileIngestionService.extractDocumentForQuestion(dto.name(), dto.contentType(), bytes, question);
        if (!Set.of("READY", "PARTIAL", "EMPTY").contains(parsed.state())) {
            com.example.lms.search.TraceStore.put("attachment.extraction.skippedReason", parsed.reasonCode());
            return List.of();
        }
        List<dev.langchain4j.data.document.Document> documents = new ArrayList<>();
        List<com.example.lms.file.FileIngestionService.TextUnit> selected = new ArrayList<>();
        int remaining = charBudget;
        // Retain both ends of long reports; parsing preserves all bounded source units.
        for (int step = 0; step < parsed.units().size() && remaining > 0; step++) {
            int index = step % 2 == 0 ? step / 2 : parsed.units().size() - 1 - step / 2;
            var unit = parsed.units().get(index);
            if (unit.text().length() > remaining) {
                int allocation = step == 0 && parsed.units().size() > 1 ? Math.max(1, remaining / 2) : remaining;
                unit = unit.excerpt(allocation, step % 2 != 0);
            }
            if (!unit.text().isBlank()) {
                selected.add(unit);
                remaining -= unit.text().length();
            }
        }
        boolean partial = parsed.state().equals("PARTIAL") || selected.stream().mapToInt(u -> u.text().length()).sum()
                < parsed.units().stream().mapToInt(u -> u.text().length()).sum();
        selected.sort(Comparator.comparingInt(com.example.lms.file.FileIngestionService.TextUnit::startOffset)
                .thenComparingInt(com.example.lms.file.FileIngestionService.TextUnit::paragraphIndex));
        for (var unit : selected) {
            Map<String, Object> meta = attachmentMetadata(dto, parsed, unit.locator());
            meta.put("locatorType", unit.locatorType());
            if (unit.lineStart() > 0) {
                meta.put("lineStart", unit.lineStart());
                meta.put("lineEnd", unit.lineEnd());
            }
            if (unit.paragraphIndex() > 0) meta.put("paragraphIndex", unit.paragraphIndex());
            if (unit.memberPath() != null) meta.put("archiveMemberPath", unit.memberPath());
            if (unit.jsonPointer() != null) meta.put("jsonPointer", unit.jsonPointer());
            meta.put("startOffset", unit.startOffset());
            meta.put("endOffset", unit.endOffset());
            meta.put("offsetEncoding", unit.paragraphIndex() > 0 ? "paragraph-utf16" : "decoded-utf16");
            meta.put("coveragePartial", Boolean.toString(partial));
            documents.add(dev.langchain4j.data.document.Document.from(unit.text(),
                    new dev.langchain4j.data.document.Metadata(meta)));
        }
        if (documents.isEmpty() && charBudget > 0 && "ARCHIVE_LIST_ONLY".equals(parsed.reasonCode())) {
            Map<String, Object> meta = attachmentMetadata(dto, parsed, "archive-list");
            meta.put("locatorType", "ARCHIVE_LIST");
            meta.put("coveragePartial", "true");
            String listing = "ARCHIVE LIST ONLY (bodyReadCount=0)\n" + String.join("\n", parsed.archiveMembers());
            if (listing.length() > charBudget) listing = listing.substring(0, charBudget);
            documents.add(dev.langchain4j.data.document.Document.from(listing,
                    new dev.langchain4j.data.document.Metadata(meta)));
        }
        if (documents.isEmpty() && "EMPTY".equals(parsed.state())) {
            com.example.lms.search.TraceStore.put("attachment.text.emptyReason", "structured_document_empty");
            com.example.lms.search.TraceStore.put("attachment.extraction.skippedReason", "structured_document_empty");
        }
        if(repo.get(dto.id())!=dto)return List.of();
        if(sourceSnapshot!=null&&!documents.isEmpty()){
            // Only parser-produced spans are stored, never model-authored source IDs or offsets.
            var units=new ArrayList<Map<String,Object>>();
            for(var document:documents){
                var unit=new LinkedHashMap<String,Object>();
                for(String key:List.of("displayName","documentRole","locator","locatorType","archiveMemberPath",
                        "jsonPointer","lineStart","lineEnd","paragraphIndex","startOffset","endOffset","offsetEncoding")){
                    Object value=document.metadata().toMap().get(key);if(value!=null)unit.put(key,value);
                }
                unit.put("text",document.text());units.add(unit);
            }
            String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(units);
            var recorded=durableStore.recordText(dto.id(),sourceSnapshot.sourceRevision(),digest,
                com.example.lms.file.FileIngestionService.PARSER_VERSION,json,partial?"PARTIAL":"TEXT_READY");
            if(recorded.isEmpty())return List.of();
            var snapshot=recorded.get();durableSnapshots.put(dto.id(),snapshot);
            for(var document:documents){
                var meta=document.metadata();String locator=meta.getString("locator");
                meta.put("sourceRevision",snapshot.sourceRevision());
                meta.put("sourceLocator","attachment:"+dto.id()+"/revisions/"+snapshot.sourceRevision()+"#"+locator);
                meta.put("chunkId",org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                    dto.id()+":"+snapshot.sourceRevision()+":"+snapshot.parserVersion()+":"+locator+":"+document.text()));
                meta.put("graphState",snapshot.graphState());meta.put("vectorState",snapshot.vectorState());
            }
        }
        return currentDto(dto.id())==dto?documents:List.of();
    }

    private Map<String, Object> attachmentMetadata(AttachmentDto dto,
            com.example.lms.file.FileIngestionService.DocumentExtraction parsed, String locator) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "attachment");
        meta.put("attachmentId", dto.id());
        meta.put("sourceId", "attachment:" + dto.id());
        meta.put("sourceRevision", 1L);
        meta.put("name", dto.name() == null ? "" : dto.name());
        meta.put("displayName", dto.name() == null ? "" : dto.name().replaceAll("\\p{Cntrl}", " "));
        meta.put("contentType", dto.contentType() == null ? "" : dto.contentType());
        meta.put("detectedMime", parsed.detectedMime());
        meta.put("contentSha256", parsed.contentSha256());
        meta.put("parserVersion", com.example.lms.file.FileIngestionService.PARSER_VERSION);
        meta.put("documentRole", String.join(",", parsed.roles()));
        meta.put("executionAuthority", "DATA_ONLY");
        meta.put("locator", locator);
        meta.put("sourceLocator", "attachment:" + dto.id() + "/revisions/1#" + locator);
        meta.put("chunkId", org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                dto.id() + ":1:" + com.example.lms.file.FileIngestionService.PARSER_VERSION + ":" + locator));
        meta.put("textState", parsed.state());
        meta.put("parseReasonCode", parsed.reasonCode());
        meta.put("graphState", "NOT_INDEXED");
        meta.put("vectorState", "NOT_INDEXED");
        meta.put("semanticExtractionReason", "TOKEN_COUNT_UNVERIFIED");
        meta.put("semanticAttempts", 0);
        meta.put("inspectedEntryCount", parsed.inspectedEntryCount());
        meta.put("bodyReadCount", parsed.bodyReadCount());
        meta.put("attachmentType", attachmentType(dto.name(), dto.contentType()));
        return meta;
    }

    public java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSession(
            java.util.List<String> ids,
            String sessionId) {
        return asDocumentsForSessionInternal(ids, sessionId, null, false, null);
    }

    public java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSession(
            java.util.List<String> ids,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity) {
        return asDocumentsForSessionInternal(
                ids,
                sessionId,
                Objects.requireNonNull(ownerIdentity, "ownerIdentity"),
                true, null);
    }

    public java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSession(
            java.util.List<String> ids, String sessionId,
            AttachmentOwnerIdentity ownerIdentity, String question) {
        return asDocumentsForSessionInternal(ids, sessionId,
                Objects.requireNonNull(ownerIdentity, "ownerIdentity"), true, question);
    }

    private java.util.List<dev.langchain4j.data.document.Document> asDocumentsForSessionInternal(
            java.util.List<String> ids,
            String sessionId,
            AttachmentOwnerIdentity ownerIdentity,
            boolean requireOwner,
            String question) {
        if (ids == null || ids.isEmpty()) {
            return asDocuments(ids);
        }
        if (sessionId == null || sessionId.isBlank()) {
            com.example.lms.search.TraceStore.put("attachment.sessionFilter.applied", false);
            com.example.lms.search.TraceStore.put("attachment.sessionFilter.reason", "missing_session");
            return requireOwner ? java.util.List.of() : asDocuments(ids);
        }
        for(String id:ids)currentDto(id);
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
        return asDocuments(filtered, question);
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

    private java.nio.file.Path resolveExistingAttachmentPath(AttachmentDto dto) {
        if (dto == null || dto.url() == null || dto.url().isBlank()) {
            return null;
        }
        try {
            if(storage!=null){
                var stored=storage.resolveStoredPath(dto.url());
                if(stored!=null&&stored.isPresent())return stored.get();
            }
            if(durableStore!=null)return null;
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
            if(durableStore!=null)for(String id:durableStore.expiredIds(nowEpochMs))
                durableStore.includingExpired(id).ifPresent(this::cacheSnapshot);
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
                currentDto(id);
                linkToSessionLocked(sessionId, id);
            }
        }
    }

    public boolean attachToSession(
            String sessionId,
            java.util.List<String> ids,
            AttachmentOwnerIdentity ownerIdentity) {
        com.example.lms.search.TraceStore.put("attachment.bind.applied", false);
        com.example.lms.search.TraceStore.put("attachment.bind.reason", "missing_session_or_ids");
        if (sessionId == null || sessionId.isBlank() || ids == null || ids.isEmpty()) {
            return false;
        }
        Objects.requireNonNull(ownerIdentity, "ownerIdentity");
        synchronized (metadataMutationLock) {
            for (String id : ids) {
                currentDto(id);
                if (id == null || id.isBlank() || !repo.containsKey(id)) {
                    com.example.lms.search.TraceStore.put("attachment.bind.reason", "metadata_missing");
                    return false;
                }
                if (!isOwnedBy(id, ownerIdentity)) {
                    com.example.lms.search.TraceStore.put("attachment.bind.reason", "owner_mismatch");
                    return false;
                }
                if (isLinkedToDifferentSession(id, sessionId)) {
                    com.example.lms.search.TraceStore.put("attachment.bind.reason", "different_session");
                    return false;
                }
            }
            for (String id : ids) {
                linkToSessionLocked(sessionId, id);
            }
            com.example.lms.search.TraceStore.put("attachment.bind.applied", true);
            com.example.lms.search.TraceStore.put("attachment.bind.reason", "bound");
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
        if(durableStore!=null&&ownerHashById.containsKey(id)
                &&!durableStore.bind(id,ownerHashById.get(id),sessionId))throw new IllegalStateException("attachment_session_changed");
        if (!existing.contains(id)) {
            existing.add(id);
        }
    }

    private MetadataDeletion deleteMetadataLocked(String id) {
        AttachmentDto existing = repo.get(id);
        if(durableStore!=null)durableStore.tombstone(id);
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
        durableSnapshots.remove(id);
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
            if(!deleted&&storage!=null&&durableStore!=null){
                var persisted=durableStore.deleted(dto.id()).filter(s->s.dto().equals(dto));
                if(persisted.isPresent())deleted=storage.deletePersisted(dto.url(),dto.size(),persisted.get().contentSha256());
            }
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
                try {deleteMetadataLocked(dto.id());}
                catch(RuntimeException unavailable){traceSuppressed("attachment.rollback.metadata",unavailable);}
                // This list contains only bytes saved by this upload, including a failed row insert.
                physicalDeletes.add(dto);
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
        currentDto(id);
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
