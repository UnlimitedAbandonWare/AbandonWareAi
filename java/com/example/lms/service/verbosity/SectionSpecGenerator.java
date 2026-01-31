package com.example.lms.service.verbosity;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;




@Component
public class SectionSpecGenerator {

    public List<String> generate(String intent, String domain, String verbosity) {
        String v = normalize(verbosity, "standard");
        String d = normalize(domain, "");

        // 1) verbosity 기본 템플릿 (standard에서도 빈 리스트 금지)
        List<String> base;
        if ("ultra".equalsIgnoreCase(v)) {
            base = List.of("요약", "핵심", "근거", "사례/예시", "주의/한계", "대안/옵션", "다음 단계");
        } else if ("deep".equalsIgnoreCase(v)) {
            base = List.of("요약", "핵심", "근거", "사례/예시", "주의/한계", "다음 단계");
        } else if ("brief".equalsIgnoreCase(v)) {
            base = List.of("요약", "핵심", "다음 단계");
        } else {
            // standard: 올라운더 기본형 (빈 리스트 방지)
            base = List.of("요약", "핵심", "추가 설명", "다음 단계");
        }

        // 2) 도메인별 오버라이드
        String du = d.toUpperCase(Locale.ROOT);
        if (du.contains("GENSHIN") || du.contains("GAME")) {
            if ("brief".equalsIgnoreCase(v)) {
                return List.of("요약", "핵심", "주의(버전/조건)", "다음 단계");
            }
            return List.of("요약", "핵심", "공략/팁", "주의(버전/조건)", "다음 단계");
        }

        if (du.contains("EDU") || du.contains("STUDY") || du.contains("EMPLOY") || du.contains("TRAIN")) {
            if ("brief".equalsIgnoreCase(v)) {
                return List.of("요약", "핵심", "확인/절차", "다음 단계");
            }
            return List.of("요약", "핵심", "조건/대상", "절차/서류", "주의/한계", "다음 단계");
        }

        return base;
    }

    private static String normalize(String v, String def) {
        if (v == null || v.isBlank()) return def;
        return v.strip().toLowerCase(Locale.ROOT);
    }
}