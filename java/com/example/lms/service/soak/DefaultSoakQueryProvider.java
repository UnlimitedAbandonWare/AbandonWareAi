package com.example.lms.service.soak;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.ArrayList;
import java.util.List;

/** Public default implementation so it can be wired from @Configuration. */
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultSoakQueryProvider implements SoakQueryProvider {
    @Override
    public List<String> queries(String topic) {
        String t = (topic == null ? "all" : topic);
        switch (t) {
            case "genshin":
                return List.of("원신 성유물 파밍 루트", "원신 풀 원소 파티 추천");
            case "default":
                return List.of("RAG 파이프라인이 뭐야?", "크로스 인코더 장단점");
            default:
                List<String> all = new ArrayList<>();
                all.addAll(queries("default"));
                all.addAll(queries("genshin"));
                return all;
        }
    }
}