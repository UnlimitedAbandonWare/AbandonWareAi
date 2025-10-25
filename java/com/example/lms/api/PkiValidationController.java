package com.example.lms.api;

import com.example.lms.service.PkiValidationStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;



@RestController
@RequestMapping("/.well-known/pki-validation")
@RequiredArgsConstructor
public class PkiValidationController {

    private final PkiValidationStorageService storageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        try {
            String url = storageService.save(file);
            return ResponseEntity.ok(new FileUploadResponse("업로드 성공", url, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new FileUploadResponse(e.getMessage(), null, false));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(new FileUploadResponse("서버 내부 오류: " + e.getMessage(), null, false));
        }
    }
}