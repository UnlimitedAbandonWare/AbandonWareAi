package com.example.lms.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;


import org.springframework.context.annotation.Primary; // + @Primary import 추가

@Primary // + 모호성 있을 때 기본 선택

@Component
public class TypoNormalizer {

    private static final Logger log = LoggerFactory.getLogger(TypoNormalizer.class);
    private final Map<String, String> map;

    public TypoNormalizer(Environment env) {
        Map<String, String> m = new HashMap<>();

        // 1) YAML/Properties 트리 바인딩 (가장 표준)
        try {
            Map<String, String> bound = Binder.get(env)
                    .bind("query-hygiene.typo-map", Bindable.mapOf(String.class, String.class))
                    .orElseGet(Collections::emptyMap);
            m.putAll(bound);
        } catch (Exception ex) {
            log.warn("Typo map bind failed (tree). Proceeding with fallbacks. cause={}", ex.toString());
        }

        // 2) 평탄키(단일 문자열)로 들어온 경우 대비: JSON 또는 'a:b,b:c' 포맷도 허용
        if (m.isEmpty()) {
            String raw = env.getProperty("query-hygiene.typo-map");
            if (raw != null && !raw.isBlank()) {
                String s = raw.trim();
                try {
                    if (s.startsWith("{") && s.endsWith("}")) {
                        // JSON 오브젝트 문자열
                        // Jackson이 클래스패스에 있으므로 사용 가능 (spring-boot-starter-web/actuator 등)
                        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                        @SuppressWarnings("unchecked")
                        Map<String, String> json = om.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                        m.putAll(json);
                    } else {
                        // 콤마 구분 '키:값,키:값' 형식
                        for (String pair : s.split(",")) {
                            String[] kv = pair.split(":", 2);
                            if (kv.length == 2) {
                                String k = kv[0].trim();
                                String v = kv[1].trim();
                                if (!k.isEmpty() && !v.isEmpty()) m.put(k, v);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Typo map parse failed (raw='{}'). cause={}", raw, ex.toString());
                }
            }
        }

        // 불변 + NPE 방지
        this.map = Collections.unmodifiableMap(m);
        log.info("TypoNormalizer loaded {} entries", this.map.size());
    }

    public String normalize(String q) {
        if (q == null || q.isBlank() || map.isEmpty()) return q;

        String r = q;
        // 긴 키 우선 치환(부분키 충돌 방지)
        List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        for (var e : entries) {
            r = r.replace(e.getKey(), e.getValue());
        }
        return r;
    }
}