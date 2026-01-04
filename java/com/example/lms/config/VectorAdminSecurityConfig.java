package com.example.lms.config;

import com.example.lms.security.VectorAdminTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dedicated security chain for vector admin endpoints.
 *
 * <p>Applies token auth (vector.admin.token) to /api/admin/vector/**.</p>
 */
@Configuration
public class VectorAdminSecurityConfig {

    @Bean
    public VectorAdminTokenFilter vectorAdminTokenFilter(@Value("${vector.admin.token:}") String token) {
        return new VectorAdminTokenFilter(token);
    }

    /**
     * Prevent Spring Boot from auto-registering the token filter for every request.
     *
     * <p>
     * The filter is still applied to {@code /api/admin/vector/**} via the
     * dedicated {@link SecurityFilterChain} below.
     * </p>
     */
    @Bean
    public FilterRegistrationBean<VectorAdminTokenFilter> vectorAdminTokenFilterRegistration(
            VectorAdminTokenFilter filter) {
        FilterRegistrationBean<VectorAdminTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain vectorAdminChain(HttpSecurity http,
                                                VectorAdminTokenFilter tokenFilter) throws Exception {

        http.securityMatcher("/api/admin/vector/**");

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("VECTOR_ADMIN")
                )
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .requestCache(cache -> cache.disable())
                .formLogin(fl -> fl.disable())
                .logout(lo -> lo.disable());

        return http.build();
    }
}
