package com.example.lms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;




@Service
public class PkiValidationStorageService {

    private final Path rootLocation;

    public PkiValidationStorageService(
            @Value("${pki.validation.upload-dir:./.well-known/pki-validation}") String uploadDir) {
        this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("PKI 업로드 디렉터리 생성 실패: " + this.rootLocation, e);
        }
    }

    /** 허용 파일명: 32~64자리 16진수 + ".txt" (대소문자 허용) */
    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드된 파일이 비어 있습니다.");
        }
        String name = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        if (!name.matches("^[A-Fa-f0-9]{32,64}\\.txt$")) {
            throw new IllegalArgumentException("허용되지 않는 파일명입니다. 예: 50B3B315378F39176F7DECC6926A29A2.txt");
        }
        // 1MB 제한
        if (file.getSize() > 1_000_000L) {
            throw new IllegalArgumentException("파일 크기가 1MB를 초과합니다.");
        }

        Path dest = rootLocation.resolve(name).normalize();
        // 디렉터리 탈출 방지
        if (!dest.getParent().equals(rootLocation)) {
            throw new IllegalArgumentException("보안 위협: 경로 탈출 감지");
        }

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패", e);
        }
        return "/.well-known/pki-validation/" + name;
    }
}