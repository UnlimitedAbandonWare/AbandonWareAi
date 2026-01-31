// src/main/java/com/example/lms/api/KakaoTriggerController.java
package com.example.lms.api;

import com.example.lms.dto.KakaoFormDto;
import com.example.lms.integrations.KakaoMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;



@Controller
@RequiredArgsConstructor
@RequestMapping("/kakao")
public class KakaoTriggerController {

    private final KakaoMessageService kakaoMsg;

    /**
     * 1) GET  /kakao/trigger : 폼 페이지 렌더링
     */
    @GetMapping("/trigger")
    public String showForm(Model model) {
        model.addAttribute("dto", new KakaoFormDto());
        return "kakao/form";  // src/main/resources/templates/kakao/form.html
    }

    /**
     * 2) POST /kakao/trigger (FORM) : application/x-www-form-urlencoded
     */
    @PostMapping(
            path = "/trigger",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public String triggerForm(
            @ModelAttribute("dto") KakaoFormDto dto,
            RedirectAttributes redirect
    ) {
        kakaoMsg.pushUrl(dto.getUserKey(), dto.getMessage(), dto.getUrl());
        redirect.addFlashAttribute("result", "메시지 발송 성공!");
        return "redirect:/kakao/trigger";
    }

    /**
     * 3) POST /kakao/trigger (JSON) : application/json
     */
    @PostMapping(
            path = "/trigger",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public ResponseEntity<Void> triggerJson(@RequestBody KakaoFormDto dto) {
        kakaoMsg.pushUrl(dto.getUserKey(), dto.getMessage(), dto.getUrl());
        return ResponseEntity.ok().build();
    }
}