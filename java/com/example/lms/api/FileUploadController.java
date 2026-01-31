package com.example.lms.api;

import com.example.lms.util.FileStorage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;



/**
 * @deprecated This controller is retained only for backward compatibility.  The
 * PKI upload functionality is now handled exclusively by {@link PkiValidationController}.
 * To avoid conflicting endpoints the base mapping has been changed to a dummy path.
 */
@Deprecated
@RestController
@RequestMapping("/deprecated/file-upload-controller")
public class FileUploadController {

    private final FileStorage fileStorage;

    // 생성자를 통한 의존성 주입
    public FileUploadController(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            // 파일 저장
            String filePath = fileStorage.save(file);

            // 파일 업로드 성공
            return ResponseEntity.ok("File uploaded successfully: " + filePath);
        } catch (Exception e) {
            // 업로드 실패
            return ResponseEntity.status(500).body("File upload failed: " + e.getMessage());
        }
    }
}