package com.example.lms.service;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.storage.LocalFileStorageService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>> sessionIndex = new java.util.concurrent.ConcurrentHashMap<>();

    private final LocalFileStorageService storage;
    /** In-memory 저장소로 첨부 메타를 보관합니다. */
    private final Map<String, AttachmentDto> repo = new ConcurrentHashMap<>();

    /**
     * Service used to extract plain text from uploaded files.  Injected via
     * constructor to allow {@link #asDocuments(List)} to delegate content
     * extraction without performing manual bean lookup.  See
     * {@link com.example.lms.file.FileIngestionService} for supported formats.
     */
    private final com.example.lms.file.FileIngestionService fileIngestionService;

    /**
     * 여러 MultipartFile을 저장하고 AttachmentDto 목록을 반환합니다.
     *
     * @param files 업로드할 파일 목록
     * @return 저장된 파일 메타 정보 목록
     */
    public List<AttachmentDto> saveAll(List<MultipartFile> files) {
        List<AttachmentDto> out = new ArrayList<>();
        if (files == null) return out;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String id = UUID.randomUUID().toString();
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
            repo.put(id, dto);
            out.add(dto);
        }
        return out;
    }

    /** 특정 ID의 첨부 메타를 조회합니다. */
    public Optional<AttachmentDto> find(String id) {
        return Optional.ofNullable(repo.get(id));
    }

    /**
     * 첨부 메타를 삭제합니다. 실제 파일 삭제는 정책에 따라 선택적으로 수행합니다.
     * @param id 첨부 ID
     */
    public void delete(String id) {
        AttachmentDto dto = repo.remove(id);
        if (dto != null) {
            log.debug("Attachment deleted: {}", id);
            // 실제 파일 삭제는 필요 시 LocalFileStorageService에 메서드를 추가하여 호출할 수 있습니다.
        }
    }

    public java.util.List<com.example.lms.dto.AttachmentDto> saveAll(java.util.List<org.springframework.web.multipart.MultipartFile> files, String sessionId) {
        java.util.List<com.example.lms.dto.AttachmentDto> dtos = saveAll(files);
        if (sessionId != null && !sessionId.isBlank()) {
            sessionIndex.computeIfAbsent(sessionId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    // AttachmentDto는 record → accessor 는 id()
                    .addAll(dtos.stream().map(com.example.lms.dto.AttachmentDto::id).toList());
        }
        return dtos;
    }

    public java.util.List<com.example.lms.dto.AttachmentDto> findBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return java.util.List.of();
        java.util.List<String> ids = sessionIndex.getOrDefault(sessionId, java.util.List.of());
        // ConcurrentHashMap does not provide a snapshot() method.  Make a shallow copy
        // to avoid concurrent modification issues while iterating.  Using a plain
        // HashMap preserves current entries at the point of invocation.
        java.util.Map<String, com.example.lms.dto.AttachmentDto> map = new java.util.HashMap<>(repo);
        java.util.List<com.example.lms.dto.AttachmentDto> out = new java.util.ArrayList<>();
        for (String id : ids) {
            com.example.lms.dto.AttachmentDto dto = map.get(id);
            if (dto != null) out.add(dto);
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
            return result;
        }
        for (String id : ids) {
            try {
                com.example.lms.dto.AttachmentDto dto = this.repo.get(id);
                if (dto == null) continue;
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
                java.nio.file.Path path = java.nio.file.Path.of(dto.url().replace('\\','/'));
                byte[] bytes;
                try {
                    bytes = java.nio.file.Files.readAllBytes(path);
                } catch (Exception initialEx) {
                    String cleaned = dto.url().replace('\\','/').replaceFirst("^/+", "");
                    java.nio.file.Path resolved = java.nio.file.Path.of(cleaned).toAbsolutePath().normalize();
                    try {
                        bytes = java.nio.file.Files.readAllBytes(resolved);
                        // Log fallback resolution for debugging: include original and resolved paths.
                        log.debug("Resolved attachment path: id={} url='{}' → '{}'", id, dto.url(), resolved);
                    } catch (Exception e) {
                        boolean existsOrig = java.nio.file.Files.exists(path);
                        boolean existsRes  = java.nio.file.Files.exists(resolved);
                        log.warn("Attachment not found: id={} url='{}' resolved='{}' existsOrig={} existsResolved={}",
                                id, dto.url(), resolved, existsOrig, existsRes);
                        continue;
                    }
                }
                // Extract plain text from the file using the injected FileIngestionService.
                String text = null;
                try {
                    text = fileIngestionService.extractText(dto.name(), dto.contentType(), bytes);
                } catch (Exception ignore) {
                    // ignore and fall back to direct conversion
                }
                // Fallback: if extraction returns null or blank, attempt to interpret the
                // bytes as UTF-8 text.  This handles unknown content types and simple
                // text files without a declared MIME type.
                if (text == null || text.isBlank()) {
                    try {
                        text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ignore) {
                        text = null;
                    }
                }
                if (text == null || text.isBlank()) continue;
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
                // LC4J 1.0.1: Document is abstract → use factory method
                dev.langchain4j.data.document.Document doc =
                        dev.langchain4j.data.document.Document.from(
                                text, new dev.langchain4j.data.document.Metadata(meta));
                result.add(doc);
            } catch (Exception ignore) {
                // ignore per-attachment failures to avoid disrupting the request
            }
        }
        return result;
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
        java.util.List<String> existing = sessionIndex.computeIfAbsent(sessionId,
                k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        for (String id : ids) {
            if (repo.containsKey(id) && !existing.contains(id)) {
                existing.add(id);
            }
        }
    }
    
}