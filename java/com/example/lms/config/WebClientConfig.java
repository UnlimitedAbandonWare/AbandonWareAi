// src/main/java/com/example/lms/config/WebClientConfig.java
package com.example.lms.config;

import com.example.lms.api.KakaoProperties;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;   // ⭐ import 추가
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@RequiredArgsConstructor
@Configuration
public class WebClientConfig {

    private final KakaoProperties kakaoProps;

    /** Google Translate API용 WebClient – 대표 빈 */
    @Bean(name = "googleTranslateWebClient")
    @Primary                                // ⭐ 대표(우선순위) 표시
    public WebClient googleTranslateWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://translation.googleapis.com")
                .build();
    }

    /* ---------- Kakao 공통 커넥터 ---------- */
    private ReactorClientHttpConnector kakaoConnector() {
        HttpClient httpClient = HttpClient.create()
                .compress(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        kakaoProps.getWebclientConnectTimeoutMs())
                .responseTimeout(
                        Duration.ofMillis(kakaoProps.getWebclientReadTimeoutMs()));

        return new ReactorClientHttpConnector(httpClient);
    }

    /** Kakao REST API용 WebClient (친구/메모) */
    @Bean(name = "kakaoWebClient")
    public WebClient kakaoWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(kakaoConnector())
                .baseUrl(kakaoProps.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoProps.getAdminKey())
                .build();
    }

    /** Kakao Biz API용 WebClient (알림톡) */
    @Bean(name = "kakaoBizWebClient")
    public WebClient kakaoBizWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(kakaoConnector())
                .baseUrl(kakaoProps.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoProps.getAdminKey())
                .build();
    }
}
