package com.example.lms.controller;

import com.example.lms.service.AdaptiveTranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * AdaptiveTranslationService를 위한 API 엔드포인트 컨트롤러.
 * WebFlux의 Mono 타입을 반환하여 비동기 처리를 지원합니다.
 */
@RestController
@RequiredArgsConstructor
public class AdaptiveTranslateController {

    private final AdaptiveTranslationService adaptiveService;

    /**
     * ✨ [개선] 컨트롤러의 반환 타입을 Mono<Map<String, String>>으로 변경하여
     * 서비스 계층의 리액티브 체인을 끊지 않고 그대로 클라이언트까지 전달합니다.
     * 이를 통해 쓰레드를 블로킹하지 않고 시스템 자원을 효율적으로 사용합니다.
     */
    @PostMapping("/api/adaptive-translate")
    public Mono<Map<String, String>> translate(@RequestBody Map<String, String> payload) {
        String sourceText = payload.get("text");
        String srcLang = payload.getOrDefault("sourceLang", "ko");
        String tgtLang = payload.getOrDefault("targetLang", "en");

        return adaptiveService.translate(sourceText, srcLang, tgtLang)
                .map(translatedText -> Map.of("translatedText", translatedText));
    }
}