// src/main/java/com/example/lms/storage/LocalFileStorageService.java
package com.example.lms.storage;

import com.example.lms.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    /** application.yml 에서 설정 → 기본값은 project-root/uploads */
    @Value("${lms.upload-dir:uploads}")
    private String rootDir;

    @Override
    public String save(MultipartFile file, String subPath) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }

        String filename = StringUtils.cleanPath(file.getOriginalFilename());
        Path targetDir  = Path.of(rootDir).resolve(subPath).normalize();
        Path targetFile = targetDir.resolve(filename);

        try {
            Files.createDirectories(targetDir);
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("파일 저장 완료 → {}", targetFile.toAbsolutePath());
            return "/" + targetFile.toString().replace('\\', '/');   // 예시 URL
        } catch (IOException e) {
            log.error("파일 저장 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 저장 실패", e);
        }
    }
}