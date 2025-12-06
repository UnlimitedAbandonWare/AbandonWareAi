// src/main/java/com/example/lms/service/ProfessorService.java
package com.example.lms.service;

import com.example.lms.domain.Professor;
import com.example.lms.repository.ProfessorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;




@Service
@Transactional
public class ProfessorService {

    private final ProfessorRepository repo;

    public ProfessorService(ProfessorRepository repo) {
        this.repo = repo;
    }

    public Professor create(String name, String email) {
        Professor p = new Professor(name, email);
        return repo.save(p);
    }

    @Transactional(readOnly = true)
    public Professor findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("교수를 찾을 수 없습니다. id=" + id));
    }

    @Transactional(readOnly = true)
    public List<Professor> findAll() {
        return repo.findAll();
    }

    /**
     * 로그인 처리용: username으로 교수 조회
     */
    @Transactional(readOnly = true)
    public Professor findByUsername(String username) {
        return repo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("교수 없음: " + username));
    }

    public Professor update(Long id, String name, String email) {
        Professor p = findById(id);
        p.setName(name);
        p.setEmail(email);
        return p;
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }
}