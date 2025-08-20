package com.example.lms.config;

import com.example.lms.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * 커스텀 보안 설정.
 *
 * <p>이 클래스는 기본 {@link com.example.lms.config.AppSecurityConfig} 설정을 오버라이드 하여
 * 다음과 같은 보안 정책을 적용합니다:</p>
 * <ul>
 *   <li>정적 리소스와 로그인/인덱스 페이지에 대한 익명 접근 허용</li>
 *   <li>그 외의 모든 요청은 인증을 요구</li>
 *   <li>CSRF 토큰을 쿠키 기반으로 저장하고 모든 API 호출에서도 검증</li>
 *   <li>로그아웃은 POST 요청으로만 허용</li>
 *   <li>모든 요청을 HTTPS로 강제</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class CustomSecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;

    @Bean
    @Order(1)  // ★ 클래스가 아니라 빈(@Bean 메서드)에 명시
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");

        http
                .securityMatcher("/admin/**")
                .userDetailsService(customUserDetailsService)
                .csrf(csrf -> csrf
                        .csrfTokenRequestHandler(handler)
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .rememberMe(rem -> rem.key("...").tokenValiditySeconds(86400).alwaysRemember(true))
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .requiresChannel(channel -> channel.anyRequest().requiresSecure());

        return http.build();
    }
}