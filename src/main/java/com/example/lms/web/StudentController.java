package com.example.lms.web;

import com.example.lms.domain.Student;
import com.example.lms.service.StudentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 학생 관리 화면 (Student CRUD)
 */
@Controller
@RequestMapping("/students")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    /** (1) 학생 목록 화면 */
    @GetMapping
    public String list(Model model) {
        List<Student> students = studentService.findAll();
        model.addAttribute("students", students);
        return "students/list";
    }

    /** (2) 신규 학생 등록 폼 */
    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("student", new Student());
        return "students/form";
    }

    /** (3) 신규 학생 등록 처리 */
    @PostMapping("/new")
    public String create(@ModelAttribute Student student,
                         RedirectAttributes rttr) {
        // password 값을 포함해 생성 메서드 호출
        studentService.createStudent(
                student.getName(),
                student.getEmail(),
                student.getPassword()
        );
        rttr.addFlashAttribute("msg", "학생이 등록되었습니다.");
        return "redirect:/students";
    }

    /** (4) 학생 수정 폼 */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Student s = studentService.findById(id);
        model.addAttribute("student", s);
        return "students/form";
    }

    /** (5) 학생 수정 처리 */
    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @ModelAttribute Student student) {
        studentService.updateStudent(id, student.getName(), student.getEmail());
        return "redirect:/students";
    }

    /** (6) 학생 삭제 처리 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        studentService.deleteStudent(id);
        return "redirect:/students";
    }
}