// src/main/java/com/example/lms/config/RestTemplateConfig.java
package com.example.lms.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
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
        return builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(6))
                .build();
    }

    /**
     * 카카오 전용 RestTemplate
     * - @Qualifier("kakaoRestTemplate") 로 주입받아
     *   rootUri, 헤더 등 고정 설정이 필요한 경우 사용
     */
    @Bean
    @Qualifier("kakaoRestTemplate")
    public RestTemplate kakaoRestTemplate(RestTemplateBuilder builder) {
        return builder
                .rootUri("https://kapi.kakao.com")
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(6))
                .build();
    }
}
