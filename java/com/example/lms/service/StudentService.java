// src/main/java/com/example/lms/service/StudentService.java
package com.example.lms.service;

import com.example.lms.domain.Student;
import com.example.lms.repository.StudentRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;




@Service
@Transactional
public class StudentService {

    private final StudentRepository repo;
    private final PasswordEncoder passwordEncoder;

    public StudentService(StudentRepository repo,
                          PasswordEncoder passwordEncoder) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public Student findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다. id=" + id));
    }

    @Transactional(readOnly = true)
    public List<Student> findAll() {
        return repo.findAll();
    }

    /** (3) 신규 학생 등록 처리 */
    public Student createStudent(String name,
                                 String email,
                                 String rawPassword) {
        Student s = Student.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                // role 은 Builder.Default("ROLE_STUDENT")가 적용됩니다.
                .build();
        return repo.save(s);
    }

    /** (5) 학생 수정 처리 */
    public Student updateStudent(Long id, String name, String email) {
        Student s = findById(id);
        s.setName(name);
        s.setEmail(email);
        return s;  // Dirty Checking
    }

    /** (6) 학생 삭제 처리 */
    public void deleteStudent(Long id) {
        repo.deleteById(id);
    }
}