// src/main/java/com/example/lms/service/rag/detector/GameDomainDetector.java
package com.example.lms.service.rag.detector;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

@Component
public class GameDomainDetector {

    private static final Set<String> GENSHIN_HINTS = Set.of(
            "원신", "genshin", "호요버스", "hoyoverse",
            // 대표 캐릭터/명칭 일부
            "에스코피에", "escoffier", "푸리나", "furina", "다이루크", "diluc", "호두", "hutao"
    );

    public String detect(String q) {
        if (!StringUtils.hasText(q)) return "GENERAL";
        String s = q.toLowerCase(Locale.ROOT);
        for (String h : GENSHIN_HINTS) {
            if (s.contains(h.toLowerCase(Locale.ROOT))) return "GENSHIN";
        }
        return "GENERAL";
    }
}
