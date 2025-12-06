// src/main/java/com/example/lms/web/ProfessorController.java
package com.example.lms.web;

import com.example.lms.domain.Professor;
import com.example.lms.service.ProfessorService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;



/** 교수 관리 CRUD */
@Controller
@RequestMapping("/professors")
public class ProfessorController {

    private final ProfessorService professorService;

    public ProfessorController(ProfessorService professorService) {
        this.professorService = professorService;
    }

    /* 목록 */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("professors", professorService.findAll());
        return "professors/list";
    }

    /* 신규 등록 폼 */
    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("professor", new Professor());   // ← 이제 정상 컴파일
        return "professors/form";
    }

    /* 신규 저장 */
    @PostMapping("/new")
    public String create(@Valid @ModelAttribute Professor professor,
                         BindingResult br) {
        if (br.hasErrors()) return "professors/form";
        professorService.create(professor.getName(), professor.getEmail());
        return "redirect:/professors";
    }

    /* 수정 폼 */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("professor", professorService.findById(id));
        return "professors/form";
    }

    /* 수정 저장 */
    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute Professor professor,
                         BindingResult br) {
        if (br.hasErrors()) return "professors/form";
        professorService.update(id,
                professor.getName(),
                professor.getEmail());
        return "redirect:/professors";
    }

    /* 삭제 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        professorService.delete(id);
        return "redirect:/professors";
    }
}