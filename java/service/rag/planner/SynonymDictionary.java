// src/main/java/service/rag/planner/SynonymDictionary.java
package service.rag.planner;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SynonymDictionary {
    // 경량 사전 (필요 시 외부 yml 주입)
    private static final Map<String, List<String>> SYN = Map.of(
        "성과 지표", List.of("KPI", "핵심 지표"),
        "매출", List.of("판매액", "Revenue"),
        "지연", List.of("Latency", "응답시간")
    );

    public List<String> expandAliases(String text) {
        List<String> out = new ArrayList<>();
        String t = text.toLowerCase(Locale.ROOT);
        SYN.forEach((k, vs) -> {
            if (t.contains(k) || vs.stream().anyMatch(v -> t.contains(v.toLowerCase(Locale.ROOT)))) {
                out.addAll(vs);
                out.add(k);
            }
        });
        return out.isEmpty() ? List.of(text) : out;
    }
}