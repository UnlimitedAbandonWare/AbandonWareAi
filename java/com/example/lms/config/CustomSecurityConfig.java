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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;



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
@ConditionalOnProperty(name = "security.force-https", havingValue = "true")
@RequiredArgsConstructor
public class CustomSecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;

    @Bean
    @Order(3)  // ↓ lower priority than the open and default chains
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");

        // Restrict this ADMIN-only chain to admin-related endpoints only.
        http.securityMatcher("/admin/**", "/api/admin/**", "/dashboard/**", "/model-settings/**");

        http
                .userDetailsService(customUserDetailsService)
                .csrf(csrf -> csrf
                        .csrfTokenRequestHandler(handler)
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        // Permit anonymous access to error pages and static assets in this chain
                        .requestMatchers(
                                "/error",
                                "/favicon.ico",
                                "/webjars/**",
                                "/css/**",
                                "/js/**",
                                "/images/**"
                        ).permitAll()
                        // All other requests under the admin matcher must have ADMIN role
                        .anyRequest().hasRole("ADMIN")
                )
                // Do not configure formLogin() here; the main authentication chain will handle login
                // Preserve remember-me configuration for admin users
                .rememberMe(rem -> rem
                        .key("/* ... *&#47;")
                        .tokenValiditySeconds(86400)
                        .alwaysRemember(true)
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .requiresChannel(channel -> channel.anyRequest().requiresSecure());

        return http.build();
    }
}