package com.example.lms.web;

import com.example.lms.domain.Category;
import com.example.lms.domain.Course;
import com.example.lms.domain.Enrollment;
import com.example.lms.service.CourseService;
import com.example.lms.service.EnrollmentService;
import com.example.lms.service.StudentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 통합된 수강신청 컨트롤러
 */
@Controller
@RequestMapping("/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final CourseService courseService;
    private final StudentService studentService;

    public EnrollmentController(EnrollmentService enrollmentService,
                                CourseService courseService,
                                StudentService studentService) {
        this.enrollmentService = enrollmentService;
        this.courseService = courseService;
        this.studentService = studentService;
    }

    /**
     * (1) 신청 현황 목록 (전체 또는 courseId/studentId 필터)
     */
    @GetMapping
    public String listAll(Model model,
                          @RequestParam(value = "courseId", required = false) Long courseId,
                          @RequestParam(value = "studentId", required = false) Long studentId) {
        List<Enrollment> list;
        if (courseId != null) {
            list = enrollmentService.getEnrollmentsByCourse(courseId);
            model.addAttribute("filterType", "course");
            model.addAttribute("filterId", courseId);
        } else if (studentId != null) {
            list = enrollmentService.getEnrollmentsByStudent(studentId);
            model.addAttribute("filterType", "student");
            model.addAttribute("filterId", studentId);
        } else {
            List<Course> courses = courseService.findAll();
            if (!courses.isEmpty()) {
                Long firstCourseId = courses.get(0).getId();
                list = enrollmentService.getEnrollmentsByCourse(firstCourseId);
                model.addAttribute("filterType", "course");
                model.addAttribute("filterId", firstCourseId);
            } else {
                list = List.of();
            }
        }
        model.addAttribute("enrollments", list);
        model.addAttribute("allCourses", courseService.findAll());
        model.addAttribute("allStudents", studentService.findAll());
        return "enrollments/list";
    }

    /**
     * (2) 수강신청 폼 화면
     */
    @GetMapping("/new")
    public String createForm(
            @RequestParam(value = "category", required = false) Category category,
            Model model) {
        List<Course> courses = (category == null)
                ? courseService.findAll()
                : courseService.findByCategory(category);
        model.addAttribute("allCourses", courses);
        model.addAttribute("categories", Category.values());
        model.addAttribute("allStudents", studentService.findAll());
        return "enrollments/form";
    }

    /**
     * (3) 수강신청 처리 (단일 선택)
     */
    @PostMapping("/new")
    public String create(
            @RequestParam("courseId") Long courseId,
            @RequestParam("studentId") Long studentId,
            RedirectAttributes rttr,
            Model model) {
        try {
            enrollmentService.enroll(courseId, studentId);
        } catch (IllegalStateException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("allCourses", courseService.findAll());
            model.addAttribute("allStudents", studentService.findAll());
            return "enrollments/form";
        }
        rttr.addFlashAttribute("msg", "수강 신청이 완료되었습니다.");
        return "redirect:/enrollments?courseId=" + courseId;
    }

    /**
     * (4) 수강 취소 처리
     */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable("id") Long id,
                         RedirectAttributes rttr) {
        enrollmentService.cancelEnrollment(id);
        rttr.addFlashAttribute("msg", "수강이 취소되었습니다.");
        return "redirect:/enrollments";
    }
}