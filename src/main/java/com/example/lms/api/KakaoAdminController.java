// src/main/java/com/example/lms/api/KakaoAdminController.java
package com.example.lms.api;

import com.example.lms.dto.KakaoFormDto;
import com.example.lms.integrations.KakaoMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자용 카카오 메시지 발송 컨트롤러
 *
 * GET  /admin/kakao      → 발송 폼 표시
 * POST /admin/kakao/send → 폼 데이터로 메시지 발송 후 리다이렉트
 */
@Controller
@RequestMapping("/admin/kakao")
@RequiredArgsConstructor
public class KakaoAdminController {

    private final KakaoMessageService kakaoMsg;

    /**
     * 카카오 발송 폼을 보여줍니다.
     */
    @GetMapping
    public String showForm(Model model) {
        model.addAttribute("dto", new KakaoFormDto());
        return "kakao-admin-form";  // src/main/resources/templates/kakao-admin-form.html
    }

    /**
     * 카카오 메시지를 발송하고 결과를 RedirectAttributes에 담아 리다이렉트합니다.
     */
    @PostMapping("/send")
    public String send(
            @ModelAttribute("dto") KakaoFormDto dto,
            RedirectAttributes redirect
    ) {
        boolean ok = kakaoMsg.pushUrl(
                dto.getUserKey(),
                dto.getMessage(),
                dto.getUrl()
        );
        redirect.addFlashAttribute(
                "result",
                ok ? "메시지 전송 성공 🎉" : "메시지 전송 실패 😢"
        );
        return "redirect:/admin/kakao";
    }
}
