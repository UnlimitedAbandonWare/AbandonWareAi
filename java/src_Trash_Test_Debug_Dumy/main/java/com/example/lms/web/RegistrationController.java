// src/main/java/com/example/lms/web/RegistrationController.java
package com.example.lms.web;

import com.example.lms.service.UserService;
import com.example.lms.web.SignupForm;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
public class RegistrationController {

    private final UserService userService;
    public RegistrationController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("form", new SignupForm());
        return "signup/form";
    }

    @PostMapping("/signup")
    public String signupSubmit(
            @Valid @ModelAttribute("form") SignupForm form,
            BindingResult br,
            Model model
    ) {
        if (br.hasErrors()) {
            // 폼 유효성 에러
            return "signup/form";
        }

        try {
            userService.register(
                    form.getUsername(),
                    form.getPassword(),
                    form.getEmail()
            );
        } catch (DataIntegrityViolationException ex) {
            // 예: 중복된 username
            br.rejectValue("username", "duplicate", "이미 사용 중인 아이디입니다.");
            return "signup/form";
        } catch (Exception ex) {
            // 그 외 오류
            model.addAttribute("errorMessage", "가입 중 문제가 발생했습니다. 다시 시도해주세요.");
            return "signup/form";
        }

        return "redirect:/login";
    }
}
