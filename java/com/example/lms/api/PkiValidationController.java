package com.example.lms.api;

import com.example.lms.api.FileUploadResponse;
import com.example.lms.service.PkiValidationStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/.well-known/pki-validation")
@RequiredArgsConstructor // final 필드에 대한 생성자를 자동으로 만들어줍니다 (Lombok)
public class PkiValidationController {

    private final PkiValidationStorageService storageService;

    @PostMapping
    public ResponseEntity<FileUploadResponse> handleFileUpload(@RequestParam("file") MultipartFile file) {
        try {
            String savedFilename = storageService.save(file);
            String fileUrl = "/.well-known/pki-validation/" + savedFilename;

            FileUploadResponse response = new FileUploadResponse(
                    "파일 업로드 성공!",
                    fileUrl,
                    true
            );
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // 잘못된 요청 (e.g., 빈 파일, 잘못된 파일명)
            FileUploadResponse response = new FileUploadResponse(e.getMessage(), null, false);
            return ResponseEntity.badRequest().body(response);

        } catch (RuntimeException e) {
            // 서버 내부 오류 (e.g., 파일 시스템 I/O 에러)
            FileUploadResponse response = new FileUploadResponse("서버 내부 오류: " + e.getMessage(), null, false);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}