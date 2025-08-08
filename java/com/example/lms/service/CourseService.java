// src/main/java/com/example/lms/service/CourseService.java
package com.example.lms.service;

import com.example.lms.domain.Course;
import com.example.lms.domain.Category;
import com.example.lms.domain.Professor;
import com.example.lms.repository.CourseRepository;
import com.example.lms.service.ProfessorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class CourseService {

    private final CourseRepository courseRepo;
    private final ProfessorService professorSvc;

    public CourseService(CourseRepository courseRepo,
                         ProfessorService professorSvc) {
        this.courseRepo   = courseRepo;
        this.professorSvc = professorSvc;
    }

    /* ───────────────── 조회 메서드 ───────────────── */
    @Transactional(readOnly = true)
    public List<Course> findAll() {
        return courseRepo.findAll();
    }

    @Transactional(readOnly = true)
    public Course findById(Long id) {
        return courseRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Course not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Course> findByProfessor(Long profId) {
        return courseRepo.findByProfessorId(profId);
    }

    // EnrollmentController 에서 사용
    @Transactional(readOnly = true)
    public List<Course> findByCategory(Category category) {
        return courseRepo.findByCategory(category);
    }

    /* ───────────────── 생성 메서드 ───────────────── */
    /**
     * 빌더를 이용한 생성
     */
    public Course create(String title, String desc, Professor professor) {
        Course c = Course.builder()
                .title(title)
                .description(desc)
                .professor(professor)
                .build();
        return courseRepo.save(c);
    }

    /**
     * id만 넘기는 기존 버전 – 내부에서 위 create(...) 재활용
     */
    public Course create(String title, String desc, Long profId) {
        Professor p = professorSvc.findById(profId);
        return create(title, desc, p);
    }

    /**
     * 컨트롤러에서 완성된 엔티티를 받아 저장할 때
     */
    public Course save(Course c) {
        return courseRepo.save(c);
    }

    /* ───────────────── 수정 / 삭제 ───────────────── */
    public void updateCourse(Long id, String title, String desc, Long profId) {
        Course c = findById(id);
        c.setTitle(title);
        c.setDescription(desc);
        if (profId != null) {
            c.setProfessor(professorSvc.findById(profId));
        }
        // JPA dirty checking → 자동 반영
    }

    public void deleteCourse(Long id) {
        courseRepo.deleteById(id);
    }
}
