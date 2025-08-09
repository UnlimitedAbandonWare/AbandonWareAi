package com.example.lms.security;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.Professor;
import com.example.lms.domain.Student;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.repository.ProfessorRepository;
import com.example.lms.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary; // ★ 추가
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 로그인 ID(username) → Spring Security UserDetails 변환
 *
 * <pre>
 *  • 우선순위
 *      1) 관리자(Administrator.username)
 *      2) 교수(Professor.username)
 *      3) 교수(Professor.email)     – 이전 버전 호환 용도
 *      4) 학생(Student.email)
 * </pre>
 */
@Service
@Primary                 // ★ 우선 적용될 UserDetailsService 로 지정
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final AdministratorRepository adminRepo;
    private final ProfessorRepository     profRepo;
    private final StudentRepository       studRepo;

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        return  adminRepo.findByUsername(username).map(this::toUserDetails)
                // 교수: username → email(호환)
                .or(() -> profRepo.findByUsername(username).map(this::toUserDetails))
                .or(() -> profRepo.findByEmail(username).map(this::toUserDetails))
                // 학생: email
                .or(() -> studRepo.findByEmail(username).map(this::toUserDetails))
                .orElseThrow(() ->
                        new UsernameNotFoundException("User not found: " + username));
    }

    /* ─────────── 엔티티 → UserDetails 매핑 ─────────── */

    private UserDetails toUserDetails(Administrator a) {
        String role = normalizeRole(a.getRole(), a.getUsername(), "Administrator");
        return User.withUsername(a.getUsername())
                .password(a.getPassword())
                .roles(role)
                .build();
    }

    private UserDetails toUserDetails(Professor p) {
        String role = normalizeRole(p.getRole(), p.getUsername(), "Professor");
        // 로그인 ID는 username 우선, 없으면 email
        String loginId = (p.getUsername() != null && !p.getUsername().isBlank())
                ? p.getUsername()
                : p.getEmail();
        return User.withUsername(loginId)
                .password(p.getPassword())
                .roles(role)
                .build();
    }

    private UserDetails toUserDetails(Student s) {
        String role = normalizeRole(s.getRole(), s.getEmail(), "Student");
        return User.withUsername(s.getEmail())
                .password(s.getPassword())
                .roles(role)
                .build();
    }

    /* ─────────── 공통 유틸 ─────────── */
    private String normalizeRole(String rawRole, String id, String who) {
        if (rawRole == null || rawRole.isBlank()) {
            throw new IllegalStateException(who + " [" + id + "] has no role assigned");
        }
        // "ROLE_X" → "X",  "X" → "X"
        return rawRole.startsWith("ROLE_") ? rawRole.substring(5) : rawRole;
    }
}
