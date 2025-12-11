// src/main/java/com/example/lms/web/AssignmentController.java
package com.example.lms.web;

import com.example.lms.service.AssignmentService;
import com.example.lms.service.AssignmentQueryService;
import com.example.lms.service.SubmissionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;




@Controller
@RequiredArgsConstructor
@RequestMapping("/assignments")
public class AssignmentController {

    private final AssignmentQueryService assignmentQuery;
    private final SubmissionQueryService submissionQuery;
    private final AssignmentService       assignmentService;

    /**
     * 1️⃣ 과제 목록
     */
    @GetMapping
    public String list(Model model,
                       @AuthenticationPrincipal UserDetails user) {
        Long studentId = Long.valueOf(user.getUsername());
        model.addAttribute("assignments",
                assignmentQuery.findForStudent(studentId));
        model.addAttribute("submissionStatus",
                submissionQuery.statusMapByStudent(studentId));
        return "assignments/list";
    }

    /**
     * 2️⃣ 과제 제출 폼 (토큰 발급)
     */
    @GetMapping("/{id}/submit")
    public String form(@PathVariable Long id,
                       @AuthenticationPrincipal UserDetails user,
                       Model model) {
        Long studentId = Long.valueOf(user.getUsername());
        // 30분짜리 업로드 토큰 발급
        String token = submissionQuery.issueUploadToken(id, studentId);
        model.addAttribute("assignment",
                assignmentQuery.findById(id));
        model.addAttribute("token", token);
        return "assignments/form";
    }

    /**
     * 3️⃣ 임시 저장 (Draft)
     */
    @PostMapping("/{id}/save")
    public String saveDraft(@PathVariable Long id,
                            @RequestParam("file") MultipartFile file,
                            @RequestParam("answerText") String answerText,
                            @AuthenticationPrincipal UserDetails user) throws IOException {
        Long studentId = Long.valueOf(user.getUsername());
        assignmentService.saveSubmission(
                id, studentId, file, answerText, true
        );
        return "redirect:/assignments";
    }

    /**
     * 4️⃣ 최종 제출
     */
    @PostMapping("/{id}/submit")
    public String submit(@PathVariable Long id,
                         @RequestParam("file") MultipartFile file,
                         @RequestParam("answerText") String answerText,
                         @AuthenticationPrincipal UserDetails user) throws IOException {
        Long studentId = Long.valueOf(user.getUsername());
        assignmentService.saveSubmission(
                id, studentId, file, answerText, false
        );
        return "redirect:/assignments";
    }
}