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
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 애플리케이션의 보안을 총괄하는 설정 클래스.
 * - 모든 HTTP 요청에 대해 인증 없이 접근을 허용하고
 *   JPA 세션 유지(OpenEntityManagerInViewFilter)로 지연 로딩 오류를 방지합니다.
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
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .userDetailsService(customUserDetailsService)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )
                .rememberMe(rem -> rem
                        .key("...")
                        .tokenValiditySeconds(24 * 60 * 60)
                        .rememberMeParameter("dummy")
                        .alwaysRemember(true)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .requiresChannel()   // HTTP 요청을 HTTPS로 리다이렉션
                .anyRequest()
                .requiresSecure();   // 모든 요청을 HTTPS로 리다이렉션

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
