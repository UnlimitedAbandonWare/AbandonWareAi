// src/main/java/com/example/lms/service/rag/genshin/ElementLexicon.java
package com.example.lms.service.rag.genshin;

import org.springframework.stereotype.Component;
import java.util.*;




@Component
public class ElementLexicon {

    // 간단 렉시콘(한/영/별칭). 필요 시 확장.
    private static final Map<GenshinElement, List<String>> KO = Map.of(
            GenshinElement.PYRO,   List.of("불", "파이로", "염", "화염"),
            GenshinElement.HYDRO,  List.of("물", "하이드로", "수"),
            GenshinElement.CRYO,   List.of("얼음", "크라이오", "빙", "서리"),
            GenshinElement.ELECTRO,List.of("번개", "일렉트로", "뇌"),
            GenshinElement.DENDRO, List.of("풀", "덴드로", "초록"),
            GenshinElement.GEO,    List.of("바위", "지오", "암"),
            GenshinElement.ANEMO,  List.of("바람", "아네모", "풍")
    );

    public static String inferElement(String... texts) {
        String joined = String.join(" ", Arrays.stream(texts)
                .filter(Objects::nonNull).toList()).toLowerCase(Locale.ROOT);
        for (var e : KO.entrySet()) {
            for (String k : e.getValue()) {
                if (joined.contains(k.toLowerCase(Locale.ROOT))) {
                    return e.getKey().name();
                }
            }
        }
        return "";
    }
}