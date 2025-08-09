// src/main/java/com/example/lms/service/UserService.java
package com.example.lms.service;

import com.example.lms.domain.User;
import com.example.lms.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * User 관련 비즈니스 로직
 */
@Service
@Transactional
public class UserService {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository  = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 신규 사용자 회원가입 처리
     * @throws DataIntegrityViolationException 중복 사용자명 등 DB 제약 위반 시
     */
    public User register(String username, String rawPassword, String email) {
        if (userRepository.existsByUsername(username)) {
            throw new DataIntegrityViolationException("이미 존재하는 사용자명입니다: " + username);
        }
        String encoded = passwordEncoder.encode(rawPassword);
        // 도메인 User의 all-args 생성자 사용
        User u = new User(username, encoded, "ROLE_USER", email);
        return userRepository.save(u);
    }

    /** 사용자명으로 Optional<User> 조회 */
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /** 사용자명으로 User 조회, 없으면 예외 */
    @Transactional(readOnly = true)
    public User getByUsername(String username) {
        return findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + username));
    }

    /** 이미 존재하는 사용자명인지 체크 */
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    /** 전체 사용자 목록 조회 */
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }
}
