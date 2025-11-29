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
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import com.example.lms.service.AdminDetailsServiceImpl;
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

    /**
     * 관리자 인증을 전담하는 DaoAuthenticationProvider 빈을 구성합니다.
     * 여러 UserDetailsService 빈이 등록된 환경에서 명시적으로 사용할 구현체를 지정하여
     * Spring Boot의 자동 구성 경고를 해소합니다. 주입되는 AdminDetailsServiceImpl은
     * 관리자 계정 조회만을 담당하며, PasswordEncoder는 기존 암호화 전략을 그대로 사용합니다.
     *
     * @param adminDetailsService 관리자 계정을 로드하는 서비스
     * @param passwordEncoder     비밀번호 인코더
     * @return 관리자 인증 프로바이더
     */
    @Bean
    public DaoAuthenticationProvider adminAuthProvider(AdminDetailsServiceImpl adminDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(adminDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
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
     * - 다른 체인이 존재한다면 그 체인들은 반드시 securityMatcher(/* ... *&#47;)로 담당 URL을 명시해야 하며,
     *   본 체인은 항상 마지막에 평가되도록 @Order(LOWEST_PRECEDENCE)로 지정한다.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)  // ★ 캐치올은 반드시 마지막
    public SecurityFilterChain appSecurity(HttpSecurity http, DaoAuthenticationProvider adminAuthProvider) throws Exception {
        // Thymeleaf 등에서 _csrf 이름으로 접근하도록 핸들러 설정
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");

        http
                // 명시적으로 관리자 인증 프로바이더를 추가합니다.
                .authenticationProvider(adminAuthProvider)
                // securityMatcher() 호출 안 함 → ★ 이 체인이 'any request' 캐치올
                .csrf(csrf -> csrf
                        // Configure CSRF once for this chain.  Use a cookie repository with
                        // non-HttpOnly cookies and set the request attribute handler so that
                        // Thymeleaf can access the token via the `_csrf` attribute.  Ignore
                        // CSRF protection for websocket/chat and settings APIs so that
                        // JSON POSTs to those endpoints are not rejected when no token is
                        // present.
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(handler)
                        .ignoringRequestMatchers("/api/chat/**",
                                "/api/chat-extra/**", "/ws/**", "/api/settings/**"))
                .userDetailsService(customUserDetailsService)
                .authorizeHttpRequests(auth -> {
                    // 필요 시 세부 인가 규칙을 상단에 추가하고, 마지막에 anyRequest().permitAll() 유지
                    auth.anyRequest().permitAll();
                })
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .rememberMe(rem -> rem
                        .key("change-this-remember-me-key") // In production, replace this with a secure remember-me key
                        .tokenValiditySeconds(24 * 60 * 60)
                        .alwaysRemember(true))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                // Disable the request cache to avoid SavedRequest loops.  When disabled
                // Spring Security will not attempt to remember the original request and
                // will simply redirect to the defaultSuccessUrl on successful login.
                .requestCache(cache -> cache.disable());
                // (dev 전용) HTTP 허용. HTTPS 강제 설정을 비활성화하여 개발 환경에서 http://로 실행될 때 리디렉션이 발생하지 않도록 한다.
                // .requiresChannel(channel -> channel
                //         .anyRequest().requiresSecure()); // 모든 요청 HTTPS 강제

        return http.build();
    }

    // 관리자 계정 초기화는 LmsApplication의 CommandLineRunner에서 처리합니다.
    // 중복 초기화를 방지하기 위해 기존 adminInitializer는 제거하였습니다.

    /**
     * Default security filter chain for non-admin requests.  This chain permits
     * anonymous access to the chat API, login page and static resources, while
     * requiring authentication for all other requests.  It is evaluated after
     * the admin chain (order 1) and before the catch-all chain (lowest precedence).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, DaoAuthenticationProvider adminAuthProvider) throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");

        http
                // 명시적으로 관리자 인증 프로바이더를 추가합니다.
                .authenticationProvider(adminAuthProvider)
                // Match all requests that are not explicitly handled by higher-priority chains
                .securityMatcher("/**")
                .userDetailsService(customUserDetailsService)
                .authorizeHttpRequests(authorize -> authorize
                        // Endpoints that should be accessible without authentication
                        .requestMatchers(
                                "/",
                                "/chat",
                                "/chat-ui",
                                "/login",
                                "/register",
                                "/logout",
                                "/error",
                                "/favicon.ico",
                                "/webjars/**",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/actuator/health",
                                "/api/public/**",
                                "/api/chat/**",
                                "/api/chat-extra/**",
                                // Allow unauthenticated access to n8n webhooks and task APIs
                                "/hooks/n8n/**",
                                "/v1/tasks/**"
                        ).permitAll()
                        // All other requests require authentication
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        // Redirect users to the chat page on successful login
                        .defaultSuccessUrl("/chat", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                )
                .csrf(csrf -> csrf
                        // Apply a consistent CSRF configuration: use a cookie-based token
                        // repository and attribute handler, and ignore token checks for
                        // chat/websocket and settings APIs.  These endpoints are called
                        // programmatically via fetch and cannot reliably include the
                        // generated token header.
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(handler)
                        .ignoringRequestMatchers("/api/chat/**",
                                "/api/chat-extra/**", "/ws/**", "/api/settings/**",
                                // Disable CSRF checks for n8n webhook and task endpoints
                                "/hooks/n8n/**", "/v1/tasks/**")
                )
                // Disable request caching to prevent infinite redirect loops
                .requestCache(cache -> cache.disable());

        return http.build();
    }
}