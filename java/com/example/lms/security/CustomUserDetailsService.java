// src/main/java/com/example/lms/security/CustomUserDetailsService.java
package com.example.lms.security;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.Professor;
import com.example.lms.domain.Student;
import com.example.lms.domain.User;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ProfessorRepository;
import com.example.lms.repository.StudentRepository;
import com.example.lms.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    private final AdministratorRepository adminRepo;
    private final ProfessorRepository     profRepo;
    private final StudentRepository       studRepo;
    private final @Lazy UserService       userService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("▶️ 로그인 시도 ID: {}", username);

        // 여러 사용자 유형을 순차적으로 조회
        return adminRepo.findByUsername(username)
                .map(admin -> {
                    log.info("✅ 관리자(Administrator)를 찾았습니다: {}", admin.getUsername());
                    // [수정!] 컴파일러의 타입 추론을 돕기 위해 UserDetails로 명시적 캐스팅을 추가합니다.
                    return (UserDetails) admin;
                })
                .or(() -> profRepo.findByUsername(username).map(prof -> {
                    log.info("✅ 교수(Professor)를 username으로 찾았습니다: {}", prof.getUsername());
                    return this.toUserDetails(prof);
                }))
                .or(() -> profRepo.findByEmail(username).map(prof -> {
                    log.info("✅ 교수(Professor)를 email로 찾았습니다: {}", prof.getEmail());
                    return this.toUserDetails(prof);
                }))
                .or(() -> studRepo.findByEmail(username).map(student -> {
                    log.info("✅ 학생(Student)을 email로 찾았습니다: {}", student.getEmail());
                    log.info("   - DB에 저장된 암호: {}", student.getPassword());
                    return this.toUserDetails(student);
                }))
                .or(() -> userService.findByUsername(username).map(user -> {
                    log.info("✅ 일반 사용자(User)를 찾았습니다: {}", user.getUsername());
                    return this.toUserDetails(user);
                }))
                .orElseThrow(() -> {
                    log.warn("❌ 최종적으로 사용자를 찾지 못했습니다: {}", username);
                    return new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username);
                });
    }

    /* ─────────── 엔티티 → UserDetails 매핑 (기존과 동일) ─────────── */

    private UserDetails toUserDetails(User u) {
        String role = normalizeRole(u.getRole(), u.getUsername(), "User");
        return org.springframework.security.core.userdetails.User.withUsername(u.getUsername())
                .password(u.getPassword())
                .roles(role)
                .build();
    }

    private UserDetails toUserDetails(Administrator a) {
        String role = normalizeRole(a.getRole(), a.getUsername(), "Administrator");
        return org.springframework.security.core.userdetails.User.withUsername(a.getUsername())
                .password(a.getPassword())
                .roles(role)
                .build();
    }

    private UserDetails toUserDetails(Professor p) {
        String role = normalizeRole(p.getRole(), p.getUsername(), "Professor");
        String loginId = (p.getUsername() != null && !p.getUsername().isBlank())
                ? p.getUsername()
                : p.getEmail();
        return org.springframework.security.core.userdetails.User.withUsername(loginId)
                .password(p.getPassword())
                .roles(role)
                .build();
    }

    private UserDetails toUserDetails(Student s) {
        String role = normalizeRole(s.getRole(), s.getEmail(), "Student");
        return org.springframework.security.core.userdetails.User.withUsername(s.getEmail())
                .password(s.getPassword())
                .roles(role)
                .build();
    }

    /* ─────────── 공통 유틸 (기존과 동일) ─────────── */

    private String normalizeRole(String rawRole, String id, String who) {
        if (rawRole == null || rawRole.isBlank()) {
            throw new IllegalStateException(who + " [" + id + "]에 역할이 할당되지 않았습니다.");
        }
        return rawRole.startsWith("ROLE_") ? rawRole.substring(5) : rawRole;
    }
}