package com.example.lms.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QueryAugmentationService {

    private static final int MAX_OUT = 4;
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "java", List.of("JVM"),
            "gpu", List.of("graphics processor", "benchmark"),
            "rag", List.of("retrieval augmented generation"));

    public List<String> augment(String original) {
        String base = clean(original);
        if (!StringUtils.hasText(base)) {
            return List.of();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(base);
        String lower = base.toLowerCase(Locale.ROOT);
        SYNONYMS.forEach((key, values) -> {
            if (lower.contains(key)) {
                values.stream()
                        .filter(StringUtils::hasText)
                        .forEach(out::add);
            }
        });

        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_OUT ? list.subList(0, MAX_OUT) : list;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().replaceAll("\\s{2,}", " ").trim();
    }
}
