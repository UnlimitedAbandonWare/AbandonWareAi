package com.example.lms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class PkiValidationStorageService {

    private final Path rootLocation;

    // application.properties에서 경로를 설정
    public PkiValidationStorageService(@Value("${pki.validation.upload-dir}") String uploadDir) {
        this.rootLocation = Paths.get(uploadDir);
    }

    public String save(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어있습니다.");
        }

        String originalFilename = file.getOriginalFilename();
        try {
            Path destinationFile = this.rootLocation.resolve(Paths.get(originalFilename)).normalize();
            // 파일 경로가 잘못되었을 경우를 방지하는 보안 로직
            if (!destinationFile.getParent().equals(this.rootLocation)) {
                throw new IllegalArgumentException("보안 위협: 파일이 지정된 경로를 벗어납니다.");
            }

            file.transferTo(destinationFile);
            return originalFilename;
        } catch (IOException e) {
            throw new RuntimeException("파일 저장에 실패했습니다.", e);
        }
    }
}
