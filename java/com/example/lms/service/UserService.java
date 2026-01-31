
// src/main/java/com/example/lms/service/UserService.java
package com.example.lms.service;

import com.example.lms.domain.Student;
import com.example.lms.domain.User;
import com.example.lms.repository.StudentRepository;
import com.example.lms.repository.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;




/**
 * 사용자 및 학생 관련 비즈니스 로직을 통합 처리하는 서비스
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 순환 참조 방지를 위해 PasswordEncoder에 @Lazy 적용
     */
    public UserService(UserRepository userRepository,
                       StudentRepository studentRepository,
                       @Lazy PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 신규 학생 회원가입
     * @param name 학생 이름 (form.getUsername())
     * @param rawPassword 비밀번호 (form.getPassword())
     * @param email 이메일 (form.getEmail())
     * @return 저장된 Student 엔티티
     * @throws DataIntegrityViolationException 이메일 중복 시
     */
    public Student register(String name, String rawPassword, String email) {
        if (studentRepository.existsByEmail(email)) {
            throw new DataIntegrityViolationException("이미 가입된 이메일입니다: " + email);
        }
        String encodedPassword = passwordEncoder.encode(rawPassword);
        Student student = Student.builder()
                .name(name)
                .email(email)
                .password(encodedPassword)
                .build();
        return studentRepository.save(student);
    }

    /**
     * 로그인 시 학생 조회 및 비밀번호 검증
     */
    @Transactional(readOnly = true)
    public Student authenticateStudent(String email, String rawPassword) {
        Student student = studentRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("등록된 학생이 없습니다: " + email));
        if (!passwordEncoder.matches(rawPassword, student.getPassword())) {
            throw new BadCredentialsException("비밀번호가 일치하지 않습니다.");
        }
        return student;
    }

    /**
     * 모든 사용자(User) 조회 (Student, Instructor 등)
     */
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * 사용자 조회, 없으면 예외
     */
    @Transactional(readOnly = true)
    public User getByUsername(String username) {
        return findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + username));
    }

    /**
     * 모든 사용자 존재 여부
     */
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * 전체 사용자 목록
     */
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }
}