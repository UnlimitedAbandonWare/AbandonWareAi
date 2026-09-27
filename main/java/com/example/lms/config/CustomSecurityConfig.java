package com.example.lms.config;

import com.example.lms.service.AdminDetailsServiceImpl;
import com.example.lms.security.AdminTokenGuardFilter;
import com.example.lms.security.AdminTokenGuardInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.UUID;


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

    private final String runtimeRememberMeKey = UUID.randomUUID().toString();

    @Value("${security.remember-me-key:${SECURITY_REMEMBER_ME_KEY:}}")
    private String rememberMeKey;

    @Value("${server.https-port:443}")
    private int httpsPort;

    @Value("${server.http-port:80}")
    private int httpPort;

    private String effectiveRememberMeKey() {
        return ConfigValueGuards.isMissing(rememberMeKey) ? runtimeRememberMeKey : rememberMeKey.trim();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain adminSecurity(HttpSecurity http,
                                      AdminDetailsServiceImpl adminDetailsService,
                                      AdminTokenGuardInterceptor adminTokenGuardInterceptor) throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");

        // Restrict this ADMIN-only chain to admin-related endpoints only.
        http.securityMatcher("/admin/**", "/api/admin/**", "/dashboard/**", "/model-settings/**", "/logout");

        http
                .userDetailsService(adminDetailsService::loadUserByUsername)
                .csrf(csrf -> csrf
                        .csrfTokenRequestHandler(handler)
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(adminTokenGuardInterceptor::isHeaderAuthorizedGraphRequest))
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
                .rememberMe(rem -> rem
                        .key(effectiveRememberMeKey())
                        .tokenValiditySeconds(24 * 60 * 60)
                        .alwaysRemember(true))
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                        .logoutSuccessUrl("/login?logout")
                        .addLogoutHandler((request, response, authentication) ->
                                adminTokenGuardInterceptor.revokePresentedSession(request, response))
                        .permitAll());

        http.addFilterBefore(
                new AdminTokenGuardFilter(adminTokenGuardInterceptor),
                UsernamePasswordAuthenticationFilter.class);

        if (httpPort > 0 && httpsPort > 0 && httpPort != httpsPort) {
            http.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort));
        }
        http.requiresChannel(channel -> channel.anyRequest().requiresSecure());

        return http.build();
    }
}
