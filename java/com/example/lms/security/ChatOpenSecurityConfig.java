package com.example.lms.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;



@Configuration
@EnableWebSecurity
public class ChatOpenSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain chatOpenChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(new OrRequestMatcher(
                AntPathRequestMatcher.antMatcher("/"),
                AntPathRequestMatcher.antMatcher("/index.html"),
                // Do not match the login endpoint here; let the main authentication chain handle it
                AntPathRequestMatcher.antMatcher("/register"),
                AntPathRequestMatcher.antMatcher("/error"),
                AntPathRequestMatcher.antMatcher("/favicon.ico"),
                AntPathRequestMatcher.antMatcher("/assets/**"),
                AntPathRequestMatcher.antMatcher("/css/**"),
                AntPathRequestMatcher.antMatcher("/js/**"),
                AntPathRequestMatcher.antMatcher("/images/**"),
                AntPathRequestMatcher.antMatcher("/chat"),
                AntPathRequestMatcher.antMatcher("/chat/**"),
                AntPathRequestMatcher.antMatcher("/chat-ui"),
                AntPathRequestMatcher.antMatcher("/chat-ui/**"),
                AntPathRequestMatcher.antMatcher("/chat:80"),
                AntPathRequestMatcher.antMatcher("/chat:80/**"),
                AntPathRequestMatcher.antMatcher("/actuator/health"),
                AntPathRequestMatcher.antMatcher("/actuator/info"),
                AntPathRequestMatcher.antMatcher("/ws/**"),
                AntPathRequestMatcher.antMatcher("/api/chat/**")
            ))
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(
                    AntPathRequestMatcher.antMatcher("/api/chat/**"),
                    AntPathRequestMatcher.antMatcher("/ws/**"),
                    AntPathRequestMatcher.antMatcher("/actuator/**")
                )
            )
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            )
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.ALWAYS))
            .anonymous(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowCredentials(true);
        cfg.addAllowedOriginPattern("*");
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}