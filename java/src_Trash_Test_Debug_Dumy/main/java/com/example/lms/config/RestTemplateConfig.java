package com.example.lms.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory; // [추가] import
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /**
     * 기본 RestTemplate 빈(@Primary)
     * - GPTService, KakaoOAuthServiceImpl 등 일반 HTTP 호출에 사용
     */
    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // 👇 [변경] Deprecated된 타임아웃 설정을 requestFactory 방식으로 변경
        return builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(6).toMillis());
                    return factory;
                })
                .build();
    }

    /**
     * 카카오 전용 RestTemplate
     * - @Qualifier("kakaoRestTemplate") 로 주입받아
     * rootUri, 헤더 등 고정 설정이 필요한 경우 사용
     */
    @Bean
    @Qualifier("kakaoRestTemplate")
    public RestTemplate kakaoRestTemplate(RestTemplateBuilder builder) {
        // 👇 [변경] Deprecated된 타임아웃 설정을 requestFactory 방식으로 변경
        return builder
                .rootUri("https://kapi.kakao.com")
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(6).toMillis());
                    return factory;
                })
                .build();
    }
}