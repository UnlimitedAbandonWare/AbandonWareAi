package com.example.lms.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Enhanced implementation of {@link FileStorageService} that stores files
 * in a date- and session-based directory structure and generates unique
 * filenames using UUIDs.  This bean is marked as {@link Primary} so
 * that it overrides the default {@code LocalFileStorageService} when
 * both are present in the Spring context.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class EnhancedLocalFileStorageService implements FileStorageService {

    /**
     * Root directory for file uploads.  Configurable via application.yml
     * property {@code app.upload.root}.  Defaults to "uploads" if not set.
     */
    @Value("${app.upload.root:uploads}")
    private String rootDir;

    @Override
    public String save(MultipartFile file, String subDir) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        // sanitize original name and extract extension
        String originalName = StringUtils.cleanPath(file.getOriginalFilename());
        String ext = "";
        int idx = originalName.lastIndexOf('.');
        if (idx >= 0) {
            ext = originalName.substring(idx);
        }
        // generate UUID-based name
        String uuid = java.util.UUID.randomUUID().toString();
        String filename = uuid + ext;
        // compute date-based directory if not provided
        String dateDir = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String subPath = (subDir != null && !subDir.isBlank()) ? subDir + "/" + dateDir : dateDir;
        Path targetDir = Path.of(rootDir).resolve(subPath).normalize();
        Path targetFile = targetDir.resolve(filename);
        try {
            Files.createDirectories(targetDir);
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("파일 저장 완료 → {}", targetFile.toAbsolutePath());
            // Construct relative URL: /{rootDir}/{subPath}/{filename}
            String normalizedRoot = rootDir.replace('\\', '/');
            String normalizedSub = subPath.replace('\\', '/');
            return "/" + normalizedRoot + "/" + normalizedSub + "/" + filename;
        } catch (IOException e) {
            log.error("파일 저장 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 저장 실패", e);
        }
    }
}