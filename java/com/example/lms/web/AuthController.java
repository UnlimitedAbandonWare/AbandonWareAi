// src/main/java/com/example/lms/web/AuthController.java
package com.example.lms.web;

import com.example.lms.service.UserService;
import com.example.lms.web.LoginForm;
import com.example.lms.web.SignupForm;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;



@Controller
public class AuthController {

    private final UserService userService;
    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /** 로그인 폼 */
    @GetMapping("/login")
    public String loginPage(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "registered", required = false) String registered,
            Model model,
            CsrfToken csrfToken            // Spring will inject
    ) {
        // CSRF 토큰
        model.addAttribute("_csrf", csrfToken);
        // 로그인 폼 바인딩 객체
        model.addAttribute("loginForm", new LoginForm());

        // 메시지
        if (registered != null) model.addAttribute("registerMessage", "회원가입이 완료되었습니다. 로그인해 주세요!");
        if (error      != null) model.addAttribute("errorMessage",    "아이디 또는 비밀번호가 올바르지 않습니다.");
        if (logout     != null) model.addAttribute("logoutMessage",   "정상적으로 로그아웃되었습니다.");

        return "auth/login";
    }

    /** 회원가입 폼 */
    @GetMapping("/register")
    public String registerForm(
            Model model,
            CsrfToken csrfToken            // Spring will inject
    ) {
        model.addAttribute("_csrf", csrfToken);
        model.addAttribute("userForm", new SignupForm());
        return "auth/register";  // templates/auth/register.html
    }

    /** 회원가입 처리 */
    @PostMapping("/register")
    public String processRegistration(
            @ModelAttribute("userForm") @Valid SignupForm form,
            BindingResult bindingResult,
            Model model,
            CsrfToken csrfToken            // need CSRF token on error redisplay
    ) {
        // CSRF 토큰 다시 올려줌
        model.addAttribute("_csrf", csrfToken);

        if (bindingResult.hasErrors()) {
            return "auth/register";
        }
        try {
            userService.register(form.getUsername(), form.getPassword(), form.getEmail());
        } catch (DataIntegrityViolationException ex) {
            bindingResult.rejectValue("username", "duplicate", "이미 사용 중인 아이디입니다.");
            return "auth/register";
        }
        return "redirect:/login?registered";
    }

    /** 에러 화면 매핑 (403 등) */
    @GetMapping("/error")
    public String error() {
        return "error";
    }
}