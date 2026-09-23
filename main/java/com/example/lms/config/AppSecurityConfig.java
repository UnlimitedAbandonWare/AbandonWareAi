package com.example.lms.config;

import com.example.lms.repository.AdministratorRepository;
import com.example.lms.security.AdminTokenGuardFilter;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.service.AdminDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.UUID;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class AppSecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(AppSecurityConfig.class);

    @SuppressWarnings("unused")
    private final AdministratorRepository adminRepo;
    private final AuthenticationConfiguration authenticationConfiguration;
    private final String runtimeRememberMeKey = UUID.randomUUID().toString();

    @Value("${security.remember-me-key:${SECURITY_REMEMBER_ME_KEY:}}")
    private String rememberMeKey;

    @Value("${security.force-https:false}")
    private boolean forceHttps;

    @Value("${server.https-port:443}")
    private int httpsPort;

    @Value("${server.http-port:80}")
    private int httpPort;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private DaoAuthenticationProvider adminAuthProvider(UserDetailsService adminDetailsService,
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

    @Bean
    public AdminTokenGuardFilter adminTokenGuardFilter(AdminTokenGuardInterceptor interceptor) {
        return new AdminTokenGuardFilter(interceptor);
    }

    @Bean
    public FilterRegistrationBean<AdminTokenGuardFilter> adminTokenGuardFilterRegistration(AdminTokenGuardFilter filter) {
        FilterRegistrationBean<AdminTokenGuardFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private String effectiveRememberMeKey() {
        return ConfigValueGuards.isMissing(rememberMeKey) ? runtimeRememberMeKey : rememberMeKey.trim();
    }

    @Bean
    @Order(0)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain probeSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/probe/**", "/internal/probe/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .anonymous(Customizer.withDefaults())
                .requestCache(cache -> cache.disable());

        if (forceHttps) {
            if (httpPort > 0 && httpsPort > 0 && httpPort != httpsPort) {
                http.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort));
            }
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        log.debug("[AWX][security] probe chain configured controllerTokenGate=true");
        return http.build();
    }

    @Bean
    @Order(2)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http,
                                                          AdminDetailsServiceImpl adminDetailsService,
                                                          PasswordEncoder passwordEncoder,
                                                          AdminTokenGuardFilter adminTokenGuardFilter,
                                                          AdminTokenGuardInterceptor adminTokenGuardInterceptor) throws Exception {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");
        org.springframework.security.web.util.matcher.RequestMatcher displayDiagnostics = request ->
                "GET".equals(request.getMethod()) && (request.getContextPath() + "/api/diagnostics/display").equals(request.getRequestURI());

        http
                .authenticationProvider(adminAuthProvider(adminDetailsService::loadUserByUsername, passwordEncoder))
                .securityMatcher("/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                AntPathRequestMatcher.antMatcher("/actuator/health"),
                                AntPathRequestMatcher.antMatcher("/actuator/info")
                        ).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).denyAll()
                        .requestMatchers(
                                "/model-settings",
                                "/model-settings/**"
                        ).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/settings", "/api/settings/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/settings", "/api/settings/**").hasRole("ADMIN")
                        .requestMatchers("/api/router", "/api/router/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/graph", "/api/admin/graph/**").hasRole("ADMIN")
                        .requestMatchers("/admin/pipeline-status").hasRole("ADMIN")
                        .requestMatchers("/api/agent/report", "/api/agent/report/**").hasRole("ADMIN")
                        .requestMatchers("/agent/db-context", "/agent/db-context/**").hasRole("ADMIN")
                        .requestMatchers("/internal/agent", "/internal/agent/**").hasRole("ADMIN")
                        .requestMatchers("/internal/soak", "/internal/soak/**").hasRole("ADMIN")
                        .requestMatchers("/internal/autoevolve", "/internal/autoevolve/**").hasRole("ADMIN")
                        .requestMatchers("/internal/nn", "/internal/nn/**").hasRole("ADMIN")
                        .requestMatchers("/flows", "/flows/**").hasRole("ADMIN")
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/fine-tuning", "/api/admin/fine-tuning/**").hasRole("ADMIN")
                        .requestMatchers("/api/internal/**").hasRole("ADMIN")
                        .requestMatchers("/api/learning/gemini", "/api/learning/gemini/**").hasRole("ADMIN")
                        .requestMatchers("/api/integrations/check").hasRole("ADMIN")
                        .requestMatchers("/v1/tasks", "/v1/tasks/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/rag/probe").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/nova/outbox/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/train", "/api/train/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/translate/train", "/api/translate/train-now").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/webhooks/channel").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/messages/trigger").hasRole("ADMIN")
                        .requestMatchers("/api/diagnostics/debug/triadic-adjudication").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/diagnostics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/diagnostics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/internal/dataset/**").permitAll()
                        .requestMatchers(
                                "/",
                                "/index",
                                "/chat",
                                "/chat-ui",
                                "/chat-ui.html",
                                "/login",
                                "/logout",
                                "/error",
                                "/favicon.ico",
                                "/webjars/**",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/api/harmony/**",
                                "/api/metrics/faithfulness",
                                "/harmony",
                                "/api/public/**",
                                "/api/chat/**",
                                "/api/chat-extra/**",
                                "/api/rag/**",
                                "/hooks/n8n/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor((request, response, failure) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(403);
                        }, displayDiagnostics)
                        .defaultAccessDeniedHandlerFor((request, response, failure) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(403);
                        }, displayDiagnostics))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/index", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .rememberMe(rem -> rem
                        .key(effectiveRememberMeKey())
                        .userDetailsService(adminDetailsService::loadUserByUsername)
                        .tokenValiditySeconds(24 * 60 * 60)
                        .alwaysRemember(true))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .addLogoutHandler((request, response, authentication) ->
                                adminTokenGuardInterceptor.revokePresentedSession(request, response))
                        .permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(handler)
                        .ignoringRequestMatchers(
                                "/api/chat/**",
                                "/api/chat-extra/**",
                                "/api/rag/**",
                                "/ws/**",
                                "/api/settings/**",
                                "/api/attachments/**",
                                "/internal/agent/**",
                                "/api/agent/report/**",
                                "/internal/dataset/**",
                                "/hooks/n8n/**",
                                "/v1/tasks/**",
                                "/api/internal/db/**")
                        .ignoringRequestMatchers(adminTokenGuardInterceptor::isHeaderAuthorizedGraphRequest)
                )
                .addFilterBefore(adminTokenGuardFilter, UsernamePasswordAuthenticationFilter.class)
                .requestCache(cache -> cache.disable());

        if (forceHttps) {
            if (httpPort > 0 && httpsPort > 0 && httpPort != httpsPort) {
                http.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort));
            }
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        log.debug("[AWX][security] default chain configured settingsAdmin=true catchAllPermitAll=false");
        return http.build();
    }
}
