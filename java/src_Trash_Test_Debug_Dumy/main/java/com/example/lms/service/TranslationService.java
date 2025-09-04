// src/main/java/com/example/lms/service/TranslationService.java
package com.example.lms.service;

import com.example.lms.translation.ApiKeyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Cloud Translation API v2 (Basic) 호출 서비스.
 * 프로젝트 ID 없이 API 키만으로 동작합니다.
 * 긴 텍스트는 분할하여 개별적으로 API를 호출하고 결과를 조합합니다.
 */
@Service
@RequiredArgsConstructor // projectId 필드가 없어졌으므로 다시 @RequiredArgsConstructor 사용 가능
public class TranslationService {

    // v2 API는 요청당 텍스트 제한이 있지만,
    // 1024자 단위로 잘라 호출하면 충분히 안정적입니다.
    private static final int MAX_CONTENT_CHARS = 1_024;

    private final ApiKeyManager apiKeyManager;
    private final RestTemplate restTemplate = new RestTemplate();

    /* ──────────────────────────────────── Public API ───────────────────────────────────── */

    public String koToEn(String text) {
        return translate(text, "ko", "en");
    }

    public String enToKo(String text) {
        return translate(text, "en", "ko");
    }

    /* ──────────────────────────────────── Core Logic ───────────────────────────────────── */

    private String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 1. v3와 동일하게 텍스트를 안전한 길이로 분할
        List<String> pieces = splitIntoPieces(text);

        // 2. 각 조각을 개별적으로 번역하고 결과를 하나로 합침
        return pieces.stream()
                .map(piece -> callV2Translate(piece, sourceLang, targetLang))
                .collect(Collectors.joining());
    }

    /**
     * 실제 Google Translate v2 API를 호출하는 메서드
     */
    private String callV2Translate(String text, String sourceLang, String targetLang) {
        // API 키 선택 (3개 중 하나를 사용하는 로직 유지)
        String apiKey = apiKeyManager.getKeyForText(text);

        // v2 API 엔드포인트 URL
        String url = UriComponentsBuilder
                .fromUriString("https://translation.googleapis.com/language/translate/v2")
                .queryParam("key", apiKey)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // v2 API 요청 바디 생성
        V2TranslateRequest body = new V2TranslateRequest(text, targetLang, sourceLang);

        Map<String, Object> resp;
        try {
            resp = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        } catch (RestClientException ex) {
            throw new RuntimeException("Google Translate API(v2) 호출 실패: " + ex.getMessage(), ex);
        }

        // v2 API 응답 구조에 맞게 파싱
        try {
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            List<Map<String, String>> translations = (List<Map<String, String>>) data.get("translations");
            return translations.get(0).get("translatedText");
        } catch (Exception e) {
            throw new RuntimeException("Google Translate API(v2) 응답 파싱 실패", e);
        }
    }

    /* ────────────────────────── Text Splitting (기존 코드 재사용) ─────────────────────────── */

    private List<String> splitIntoPieces(String text) {
        List<String> results = new ArrayList<>();
        // 문단 -> 문장 순으로 분할
        for (String para : text.split("\\r?\\n\\r?\\n")) {
            for (String sentence : para.split("(?<=[.!?])\\s+")) {
                addSafely(results, sentence);
            }
        }
        return results;
    }

    private void addSafely(List<String> list, String segment) {
        if (segment.codePointCount(0, segment.length()) <= MAX_CONTENT_CHARS) {
            list.add(segment);
            return;
        }
        // 1024자가 넘는 경우 공백/쉼표 기준으로 추가 분할
        String[] tokens = segment.split("((?<=,)|\\s)");
        StringBuilder buf = new StringBuilder();
        for (String tok : tokens) {
            if (buf.codePointCount(0, buf.length() + tok.length()) > MAX_CONTENT_CHARS) {
                list.add(buf.toString());
                buf = new StringBuilder(tok);
            } else {
                buf.append(tok);
            }
        }
        if (buf.length() > 0) list.add(buf.toString());
    }

    /* ──────────────────────────────────── DTO for v2 ───────────────────────────────────── */

    /**
     * v2 API 요청 바디를 위한 DTO (record)
     * @param q 번역할 텍스트 (query)
     * @param target 목표 언어
     * @param source 원본 언어
     */
    private record V2TranslateRequest(String q, String target, String source) {}
}