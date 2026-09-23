package com.example.lms.util;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class FileStorage {

    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".csv", ".json",
            ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx", ".hwp", ".hwpx"
    );
    private static final Path DEFAULT_UPLOAD_DIR = Paths.get("uploads", "legacy");

    public String save(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new RuntimeException("Empty upload is not allowed");
            }
            if (file.getSize() > MAX_UPLOAD_BYTES) {
                throw new RuntimeException("Upload exceeds maximum size");
            }

            String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            String extension = extensionOf(original);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new RuntimeException("File extension is not allowed");
            }

            Path root = uploadDir().toAbsolutePath().normalize();
            Files.createDirectories(root);

            String filename = UUID.randomUUID() + extension;
            Path target = root.resolve(filename).normalize();
            if (!target.startsWith(root)) {
                throw new RuntimeException("Invalid upload path");
            }

            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/legacy/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("File upload failed", e);
        }
    }

    private static String extensionOf(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot).toLowerCase(Locale.ROOT);
    }

    private static Path uploadDir() {
        String configured = System.getProperty("lms.upload.dir");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_UPLOAD_DIR;
        }
        return Paths.get(configured);
    }
}
