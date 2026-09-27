package com.example.lms.api;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.conversation.archive.ConversationArchiveIngestReport;
import com.example.lms.conversation.archive.ConversationArchiveIngestService;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.AttachmentInspectionService;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;




/**
 * 泥⑤? ?뚯씪 ?낅줈????젣瑜?泥섎━?섎뒗 REST 而⑦듃濡ㅻ윭.
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private static final int MAX_FILES = 16;
    private static final long MAX_AGGREGATE_BYTES = 25L * 1024L * 1024L;

    private final AttachmentService attachmentService;
    private final AttachmentInspectionService attachmentInspectionService;
    private final ConversationArchiveIngestService conversationArchiveIngestService;
    private final ChatHistoryService historyService;
    private final ClientOwnerKeyResolver ownerKeyResolver;

    @Autowired
    public AttachmentController(
            AttachmentService attachmentService,
            AttachmentInspectionService attachmentInspectionService,
            ConversationArchiveIngestService conversationArchiveIngestService,
            ChatHistoryService historyService,
            ClientOwnerKeyResolver ownerKeyResolver) {
        this.attachmentService = attachmentService;
        this.attachmentInspectionService = attachmentInspectionService;
        this.conversationArchiveIngestService = conversationArchiveIngestService;
        this.historyService = historyService;
        this.ownerKeyResolver = ownerKeyResolver;
    }

    /**
     * ?щ윭 ?뚯씪???낅줈?쒗븯怨?AttachmentDto 紐⑸줉??諛섑솚?⑸땲??
     * @param files multipart/form-data 濡??꾩넚???뚯씪??     * @return ?낅줈?쒕맂 ?뚯씪?ㅼ쓽 硫뷀? ?뺣낫
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<AttachmentDto> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            Authentication authentication
    ) {
        validateFiles(files);
        AttachmentOwnerIdentity ownerIdentity = authorizeAndResolveOwner(sessionId, authentication);
        if (sessionId == null || sessionId.isBlank()) {
            return attachmentService.saveAll(files, ownerIdentity);
        } else {
            return attachmentService.saveAll(files, sessionId, ownerIdentity);
        }
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> inspect(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            Authentication authentication
    ) {
        validateFiles(files);
        AttachmentOwnerIdentity ownerIdentity = authorizeAndResolveOwner(sessionId, authentication);
        List<MultipartFile> safeFiles = files == null ? List.of() : files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        List<AttachmentDto> saved = (sessionId == null || sessionId.isBlank())
                ? attachmentService.saveAll(safeFiles, ownerIdentity)
                : attachmentService.saveAll(safeFiles, sessionId, ownerIdentity);
        return attachmentInspectionService.inspect(safeFiles, sessionId, saved);
    }

    @PostMapping(value = "/conversation-archive/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationArchiveIngestReport ingestConversationArchive(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            Authentication authentication
    ) {
        validateFiles(files);
        authorizeAndResolveOwner(sessionId, authentication);
        return conversationArchiveIngestService.ingest(files, sessionId);
    }

    /**
     * ?뱀젙 泥⑤?瑜??쒓굅?⑸땲?? ?꾩옱??硫뷀? ??μ냼?먯꽌留??쒓굅?섍퀬 ?뚯씪 ??젣???섑뻾?섏? ?딆뒿?덈떎.
     * @param id 泥⑤? ID
     */
    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable String id,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            Authentication authentication
    ) {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_attachment_id");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_session");
        }
        AttachmentOwnerIdentity ownerIdentity = authorizeAndResolveOwner(sessionId, authentication);
        if (!attachmentService.deleteForSession(id, sessionId, ownerIdentity)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment_not_found");
        }
    }

    private AttachmentOwnerIdentity authorizeAndResolveOwner(
            String sessionId,
            Authentication authentication) {
        if (sessionId == null || sessionId.isBlank()) {
            return currentOwnerIdentity(authentication);
        }
        if (sessionId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_session");
        }
        long numericSessionId;
        try {
            numericSessionId = Long.parseLong(sessionId);
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_session");
        }
        if (numericSessionId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_session");
        }
        ChatSession session = historyService.getSessionWithMessages(numericSessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found");
        }
        if (session.getAdministrator() != null) {
            String expectedUsername = session.getAdministrator().getUsername();
            String actualUsername = authenticatedUsername(authentication);
            if (actualUsername == null || !actualUsername.equals(expectedUsername)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session_forbidden");
            }
            return AttachmentOwnerIdentity.forAdministrator(expectedUsername);
        }
        String expectedOwnerKey = session.getOwnerKey();
        String actualOwnerKey = ownerKeyResolver.ownerKey();
        if (expectedOwnerKey == null || !expectedOwnerKey.equals(actualOwnerKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session_forbidden");
        }
        return AttachmentOwnerIdentity.forAnonymous(expectedOwnerKey);
    }

    private AttachmentOwnerIdentity currentOwnerIdentity(Authentication authentication) {
        String username = authenticatedUsername(authentication);
        if (username != null) {
            return AttachmentOwnerIdentity.forAdministrator(username);
        }
        return AttachmentOwnerIdentity.forAnonymous(ownerKeyResolver.ownerKey());
    }

    private static String authenticatedUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()
                || "anonymousUser".equalsIgnoreCase(name)
                || "anonymous".equalsIgnoreCase(name)) {
            return null;
        }
        return name;
    }

    private static void validateFiles(List<MultipartFile> files) {
        int count = files == null ? 0 : files.size();
        if (count > MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_files");
        }
        long total = 0L;
        if (files == null) {
            return;
        }
        for (MultipartFile file : files) {
            if (file == null) {
                continue;
            }
            long size = file.getSize();
            if (size < 0L || size > MAX_AGGREGATE_BYTES - total) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "attachments_too_large");
            }
            total += size;
        }
    }
}
