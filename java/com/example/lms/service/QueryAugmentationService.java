package com.example.lms.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;




@Service
public class QueryAugmentationService {

    // 인물 탐지: 직함(교수/의사/전문의/박사) 또는 존칭(님/씨)로 끝나는 경우 위주
    // 한국어 오탑매칭(예: '의사소통')을 줄이기 위해 공백 경계/문장 끝을 고려
    private static final Pattern PERSON = Pattern.compile(
            "(^|\\s)(교수|의사|의료진|전문의|박사)(님)?(\\s|$)|(님|씨)$"
    );

    // 하드웨어/성능 키워드
    private static final Pattern HARDWARE = Pattern.compile("(그래픽|GPU|그래픽카드|iGPU|APU|성능|순위|스펙)");

    // 문장 끝 군더더기 제거(?, 공백 포함)
    private static final Pattern TRAILING_FILLER = Pattern.compile("(뭐지|알려줘|좀|요|주세요|같아|인가요|인가|할까|해줘)[?\\s]*$");

    private static final int MAX_OUT = 4;

    // 특정 주요 키워드의 동의어/연관어 매핑
    private static final Map<String, List<String>> SYNONYM_MAP = Map.of(
            "자바", List.of("JVM", "스프링"),
            "갤럭시 폴드", List.of("z fold", "z 폴드")
    );

    public List<String> augment(String original) {
        String base = clean(original);
        if (!StringUtils.hasText(base)) return List.of();

        boolean isPerson   = PERSON.matcher(base).find();
        boolean isHardware = HARDWARE.matcher(base).find();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(base); // 항상 원본 우선

        // 동의어 확장: 키워드가 포함된 경우 매핑된 동의어를 추가한다
        String baseLower = base.toLowerCase(Locale.ROOT);
        SYNONYM_MAP.forEach((key, synonyms) -> {
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (baseLower.contains(keyLower)) {
                for (String syn : synonyms) {
                    if (syn != null && !syn.isBlank()) {
                        out.add(syn);
                    }
                }
            }
        });

        if (isPerson) {
            // 사람: 최소 확장(프로필/소속/경력)
            addIfAbsent(out, base, " 프로필");
            addIfAbsent(out, base, " 소속");
            addIfAbsent(out, base, " 경력");
        } else if (isHardware) {
            // 하드웨어: iGPU 표기 보정 + 성능 관련만
            if (base.contains("내장")) {
                out.add(base.replace("내장", "iGPU"));
            }
            addIfAbsent(out, base, " 성능 순위");
            addIfAbsent(out, base, " 벤치마크");
        } else {
            // 일반: 노이즈 생성 금지 - 원본만 유지
        }

        // 상한 적용
        List<String> list = new ArrayList<>(out);
        return (list.size() > MAX_OUT) ? list.subList(0, MAX_OUT) : list;
    }

    private static void addIfAbsent(LinkedHashSet<String> out, String base, String suffix) {
        String candidate = base + suffix;
        if (!out.contains(candidate)) {
            out.add(candidate);
        }
    }

    private String clean(String s) {
        if (s == null) return "";
        String t = s.strip();
        t = TRAILING_FILLER.matcher(t).replaceAll("");
        t = t.replaceAll("\\s{2,}", " ").trim();
        return t;
    }
}