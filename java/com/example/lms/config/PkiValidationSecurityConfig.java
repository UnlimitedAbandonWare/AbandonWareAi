package com.example.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;



/**
 * DCV(도메인 소유권 검증) 전용 보안 체인.
 * - GET /.well-known/pki-validation/** : 누구나 열람 가능(리다이렉트 없이 200 OK)
 * - POST /.well-known/pki-validation   : 업로드 허용 + CSRF 예외
 * - 다른 요청과 혼선 방지를 위해 가장 먼저 평가(@Order(HIGHEST_PRECEDENCE)).
 */
@Configuration
public class PkiValidationSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain pkiValidationChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/.well-known/pki-validation/**", "/.well-known/pki-validation")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/.well-known/pki-validation/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/.well-known/pki-validation").permitAll()
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                new AntPathRequestMatcher("/.well-known/pki-validation", "POST"),
                new AntPathRequestMatcher("/.well-known/pki-validation/**", "GET")
            ));
        return http.build();
    }
}