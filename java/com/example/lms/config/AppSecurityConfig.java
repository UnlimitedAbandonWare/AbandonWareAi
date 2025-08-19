package com.example.lms.config;

import com.example.lms.domain.Administrator;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * 애플리케이션의 보안을 총괄하는 설정 클래스.
 * - 이 체인은 'any request' 캐치올 체인으로, 항상 마지막(@Order LOWEST_PRECEDENCE)으로 두어
 *   다른 체인(예: 별도 API 체인)이 securityMatcher로 범위를 명시했을 때 충돌을 피한다.
 * - CSRF 보호 및 HTTPS 강제, Remember-me, Form Login 등을 구성한다.
 */
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@Slf4j
public class AppSecurityConfig {

    private final AdministratorRepository adminRepo;
    private final CustomUserDetailsService customUserDetailsService;
    private final AuthenticationConfiguration authenticationConfiguration;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager() throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> openEntityManagerInViewFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new OpenEntityManagerInViewFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }

    /**
     * 캐치올(SecurityMatcher 미지정) 보안 필터 체인.
     * - 다른 체인이 존재한다면 그 체인들은 반드시 securityMatcher(...)로 담당 URL을 명시해야 하며,
     *   본 체인은 항상 마지막에 평가되도록 @Order(LOWEST_PRECEDENCE)로 지정한다.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)  // ★ 캐치올은 반드시 마지막
    public SecurityFilterChain appSecurity(HttpSecurity http) throws Exception {
        // Thymeleaf 등에서 _csrf 이름으로 접근하도록 핸들러 설정
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");

        http
                // securityMatcher() 호출 안 함 → ★ 이 체인이 'any request' 캐치올
                .csrf(csrf -> csrf
                        // API는 별도 체인이 없다면 임시로 CSRF 제외 (별도 API 체인을 두는 경우 여기 제거)
                        .ignoringRequestMatchers(new AntPathRequestMatcher("/api/**"))
                        .csrfTokenRequestHandler(handler)
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .userDetailsService(customUserDetailsService)
                .authorizeHttpRequests(auth -> auth
                        // 필요 시 세부 인가 규칙을 상단에 추가하고, 마지막에 anyRequest().permitAll() 유지
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .rememberMe(rem -> rem
                        .key("change-this-remember-me-key") // 실제 운영 키로 교체
                        .tokenValiditySeconds(24 * 60 * 60)
                        .alwaysRemember(true))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .requiresChannel(channel -> channel
                        .anyRequest().requiresSecure()); // 모든 요청 HTTPS 강제

        return http.build();
    }

    @Bean
    public ApplicationRunner adminInitializer(PasswordEncoder passwordEncoder) {
        return args -> adminRepo.findByUsername("admin")
                .ifPresentOrElse(
                        admin -> log.info("✅ 'admin' 계정이 이미 존재합니다."),
                        () -> {
                            log.warn("⚠️ 'admin' 계정이 없어 새로 생성합니다. (기본 비밀번호: aa0526)");
                            Administrator administrator = new Administrator(
                                    "admin",
                                    passwordEncoder.encode("aa0526"),
                                    "ROLE_ADMIN"
                            );
                            adminRepo.save(administrator);
                        }
                );
    }
}
