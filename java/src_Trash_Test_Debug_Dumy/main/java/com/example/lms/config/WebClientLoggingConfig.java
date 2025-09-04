package com.example.lms.config;

import com.example.lms.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientLoggingConfig {

    private final SettingsService settings;

    @Bean
    public WebClientCustomizer debugLoggingCustomizer() {
        return builder -> {
            boolean enabled = false;
            try {
                String v = settings.get("debug.webclient.logging.enabled");
                enabled = "true".equalsIgnoreCase(v);
            } catch (Throwable ignore) {}
            if (!enabled) return;

            builder.filter((request, next) -> {
                Instant start = Instant.now();
                log.info("[HTTP] {} {}", request.method(), request.url());
                return next.exchange(request)
                        .doOnError(err -> log.warn("[HTTP] ERROR {} {} → {}", request.method(), request.url(), err.toString()))
                        .flatMap(resp -> {
                            Duration took = Duration.between(start, Instant.now());
                            log.info("[HTTP] <-- {} {} ({} ms)", resp.statusCode().value(), request.url(), took.toMillis());
                            return Mono.just(resp);
                        });
            });
        };
    }
}
