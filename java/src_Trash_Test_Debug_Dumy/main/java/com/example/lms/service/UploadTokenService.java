// src/main/java/com/example/lms/service/UploadTokenService.java
package com.example.lms.service;

import com.example.lms.domain.Assignment;
import com.example.lms.domain.Student;
import com.example.lms.domain.UploadToken;
import com.example.lms.repository.UploadTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadTokenService {

    private final UploadTokenRepository repo;

    /**
     * 주어진 과제(asg)와 학생(stu)에 대해,
     * ttl 만큼 유효한 업로드 토큰을 발급하고 저장합니다.
     *
     * @param asg  과제 엔티티
     * @param stu  학생 엔티티
     * @param ttl  토큰 유효기간 (Duration)
     * @return 저장된 UploadToken
     */
    public UploadToken issue(Assignment asg, Student stu, Duration ttl) {
        UploadToken ut = UploadToken.builder()
                // 랜덤 UUID 문자열 (하이픈 제거)
                .token(UUID.randomUUID().toString().replace("-", ""))
                // 연관된 과제, 학생 설정
                .assignment(asg)
                .student(stu)
                // 현재 시각 기준 ttl 후 만료
                .expiresAt(LocalDateTime.now().plus(ttl))
                .build();  // ← builder() 호출부
        return repo.save(ut);
    }
}
