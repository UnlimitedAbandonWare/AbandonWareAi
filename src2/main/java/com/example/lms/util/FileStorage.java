package com.example.lms.util;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/**
 * 간단한 로컬 파일 저장 유틸.
 *  - uploads/ 디렉터리에 파일을 저장하고
 *  - 저장된 상대 경로(또는 URL)를 반환한다.
 */
@Component
public class FileStorage {

    private final Path root = Paths.get("uploads");

    public String save(MultipartFile file) {
        try {
            if (Files.notExists(root)) {
                Files.createDirectories(root);
            }
            String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Files.copy(file.getInputStream(),
                    root.resolve(filename),
                    StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/" + filename;   // 필요 시 URL 매핑에 맞춰 수정
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패", e);
        }
    }
}
