package com.example.lms.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 규칙 기반 질의 향상 서비스.
 * 불필요한 구어 제거, 숫자/연도/핵심 명사 보존
 * 동의어/관련어 소량 확장
 * LLM 호출 없이 동작 (비용 절감)
 */
@Service
public class QueryAugmentationService {
    private static final Pattern FILLER = Pattern.compile("(뭐지|알려줘|좀|요|주세요|같아|인가요|인가|할까|해줘)$");

    public List<String> augment(String original) {
        String base = clean(original);
        if (!StringUtils.hasText(base)) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(base);

        // 1) 일반 확장
        out.add(base + " 최신");
        out.add(base + " 정리");
        out.add(base + " 비교");

        // 2) 하드웨어/성능 패턴 대응
        if (Pattern.compile("(그래픽|GPU|그래픽카드|iGPU|APU|성능|순위|스펙)").matcher(base).find()) {
            out.add(base.replace("내장", "iGPU"));
            out.add(base + " 성능 순위");
            out.add(base + " 벤치마크");
        }

        // 3) 과도 확장 방지
        List<String> list = new ArrayList<>(out);
        return list.size() > 6 ? list.subList(0, 6) : list;
    }

    private String clean(String s) {
        if (s == null) return "";
        String t = s.strip();
        t = FILLER.matcher(t).replaceAll("");
        t = t.replaceAll("\\s{2,}", " ").trim();
        return t;
    }
}