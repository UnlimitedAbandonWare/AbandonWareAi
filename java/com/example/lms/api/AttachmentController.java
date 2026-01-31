package com.example.lms.api;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;




/**
 * 첨부 파일 업로드/삭제를 처리하는 REST 컨트롤러.
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * 여러 파일을 업로드하고 AttachmentDto 목록을 반환합니다.
     * @param files multipart/form-data 로 전송된 파일들
     * @return 업로드된 파일들의 메타 정보
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<AttachmentDto> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        // Delegate to the AttachmentService.  When sessionId is provided cache the
        // attachments against the session so they can be retrieved later via the
        // AttachmentContextHandler.  When not provided, simply save the files.
        if (sessionId == null || sessionId.isBlank()) {
            return attachmentService.saveAll(files);
        } else {
            return attachmentService.saveAll(files, sessionId);
        }
    }

    /**
     * 특정 첨부를 제거합니다. 현재는 메타 저장소에서만 제거하고 파일 삭제는 수행하지 않습니다.
     * @param id 첨부 ID
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        attachmentService.delete(id);
    }
}