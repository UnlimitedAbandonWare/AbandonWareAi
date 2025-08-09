// 경로: src/main/java/com/example/lms/config/AppSecurityConfig.java
package com.example.lms.config;

import com.example.lms.domain.Administrator;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class AppSecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final AdministratorRepository adminRepo;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .userDetailsService(userDetailsService)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/chat", "/login", "/register",
                                "/api/login", // AJAX 로그인 API 허용
                                "/model-settings/**",
                                "/css/**", "/js/**", "/upload/**", "/kakao/**", "/favicon.ico"
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );
        return http.build();
    }

    /**
     * 애플리케이션 시작 시, 기본 'admin' 계정이 없으면 생성하는 로직
     */
    @Bean
    public ApplicationRunner adminInitializer(PasswordEncoder encoder) {
        return args -> adminRepo.findByUsername("admin")
                .ifPresentOrElse(
                        admin -> log.info("✅ 'admin' 계정이 이미 존재합니다."),
                        () -> {
                            // ✨ [수정] 이제 애플리케이션이 직접 올바른 비밀번호로 계정을 생성합니다.
                            log.warn("⚠️ 'admin' 계정이 없어 새로 생성합니다. (기본 비밀번호: aa0526)");
                            Administrator administrator = new Administrator(
                                    "admin",
                                    encoder.encode("aa0526"), // 'aa0526'을 BCrypt로 암호화하여 저장
                                    "ROLE_ADMIN"
                            );
                            adminRepo.save(administrator);
                        }
                );
    }
}