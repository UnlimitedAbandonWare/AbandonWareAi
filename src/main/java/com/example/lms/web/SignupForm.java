// src/main/java/com/example/lms/web/SignupForm.java
package com.example.lms.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 폼 DTO
 */
public class SignupForm {

    // --- 이 필드는 이제 '이름'을 나타냅니다 ---
    @NotBlank(message = "이름은 필수 입력입니다.")
    @Size(min = 1, max = 20, message = "이름은 {min}자에서 {max}자 사이여야 합니다.")
    private String username;

    @NotBlank(message = "비밀번호는 필수 입력입니다.")
    @Size(min = 6, max = 100, message = "비밀번호는 {min}자 이상이어야 합니다.")
    private String password;

    @NotBlank(message = "이메일은 필수 입력입니다.")
    @Email(message = "유효한 이메일 주소를 입력해주세요.")
    private String email;

    // --- Getter / Setter ---
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}