// src/main/java/com/example/lms/service/StudentService.java
package com.example.lms.service;

import com.example.lms.domain.Student;
import com.example.lms.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class StudentService {

    private final StudentRepository repo;

    public StudentService(StudentRepository repo) {
        this.repo = repo;
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
    public Student createStudent(String name, String email) {
        Student s = new Student(name, email);
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
