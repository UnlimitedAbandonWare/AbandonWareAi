// src/main/java/com/example/lms/web/UploadController.java
package com.example.lms.web;

import com.example.lms.domain.Assignment;
import com.example.lms.domain.UploadToken;
import com.example.lms.service.SubmissionService;
import com.example.lms.repository.UploadTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.LocalDateTime;




@Controller
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadTokenRepository tokenRepo;
    private final SubmissionService submissionSvc;

    /** 업로드 폼(GET)  */
    @GetMapping
    public String form(@RequestParam String token, Model model) {

        UploadToken ut = tokenRepo.findByToken(token)
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException("만료되었거나 잘못된 토큰입니다."));

        Assignment asg = ut.getAssignment();
        model.addAttribute("asg", asg);
        model.addAttribute("token", token);   // 다시 POST로 넘겨주기 위해
        return "upload/form";
    }

    /** 파일 업로드(POST)  */
    @PostMapping
    public String submit(@RequestParam String token,
                         @RequestParam MultipartFile file,
                         RedirectAttributes ra,
                         HttpServletRequest req) {

        UploadToken ut = tokenRepo.findByToken(token)
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException("만료되었거나 잘못된 토큰입니다."));

        submissionSvc.saveSubmission(ut.getAssignment().getId(),
                ut.getStudent().getId(),
                file,
                req.getRemoteAddr());      // IP 로깅 예시

        /* 토큰 1회성 소모 처리 */
        tokenRepo.delete(ut);

        ra.addFlashAttribute("msg", "제출이 완료되었습니다!");
        return "redirect:/upload/done";
    }

    @GetMapping("/done")
    public String done() { return "upload/done"; }
}