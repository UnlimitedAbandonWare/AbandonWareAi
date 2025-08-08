// 경로: src/main/java/com/example/lms/service/AdminService.java
package com.example.lms.service;

import com.example.lms.domain.Administrator;
import com.example.lms.repository.AdministratorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // ✨ [수정] log를 사용하기 위해 import 추가
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자(Administrator) 관련 비즈니스 로직을 담당.
 */
@Slf4j // ✨ [수정] log 변수를 사용하기 위해 어노테이션 추가
@Service
@Transactional
@RequiredArgsConstructor
public class AdminService {

    private final AdministratorRepository repo;
    private final PasswordEncoder         encoder;

    /* ───────── 새 관리자 생성 ───────── */
    public Administrator create(String username, String rawPw, String name) {
        Administrator admin = new Administrator(username, encoder.encode(rawPw), name);
        return repo.save(admin);
    }

    /* ───────── ID 조회 ───────── */
    @Transactional(readOnly = true)
    public Administrator findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Admin not found: id=" + id));
    }

    /* ───────── 현재 로그인한 관리자 ───────── */
    @Transactional(readOnly = true)
    public Administrator getCurrentAdmin() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Administrator admin) {
            return admin;
        }
        throw new IllegalStateException("Current principal is not an Administrator: " + principal);
    }

    /**
     * ✨ [추가] 사용자 이름으로 관리자를 찾아 비밀번호를 변경하는 메서드
     * @param username 변경할 계정의 아이디
     * @param newRawPassword 암호화되지 않은 새 비밀번호
     */
    @Transactional
    public void changePassword(String username, String newRawPassword) {
        // 1. 사용자 이름으로 관리자 계정을 찾습니다. 없으면 예외를 발생시킵니다.
        Administrator admin = repo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException(username + " 계정을 찾을 수 없습니다."));

        // 2. 새로운 비밀번호를 BCrypt 방식으로 암호화합니다.
        admin.setPassword(encoder.encode(newRawPassword));

        // 3. 변경된 비밀번호를 DB에 저장합니다.
        repo.save(admin);
        log.info("'{}' 계정의 비밀번호가 변경되었습니다.", username);
    }
}