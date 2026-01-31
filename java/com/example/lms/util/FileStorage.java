package com.example.lms.util;

import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;


import org.springframework.stereotype.Component;  // Component 어노테이션 추가

@Component  // Spring 빈으로 등록
public class FileStorage {

    // 업로드 경로를 .well-known/pki-validation으로 설정
    private static final Path UPLOAD_DIR = Paths.get("src/main/resources/static/.well-known/pki-validation");

    // 파일 저장 메서드
    public String save(MultipartFile file) {
        try {
            // 업로드 경로가 존재하지 않으면 디렉토리 생성
            if (Files.notExists(UPLOAD_DIR)) {
                Files.createDirectories(UPLOAD_DIR);
            }

            // 파일 이름은 고유한 UUID로 생성하여 저장
            String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = UPLOAD_DIR.resolve(filename);

            // 파일 저장
            file.transferTo(filePath);
            return "/.well-known/pki-validation/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패", e);
        }
    }
}