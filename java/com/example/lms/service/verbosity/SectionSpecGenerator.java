package com.example.lms.service.verbosity;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;




@Component
public class SectionSpecGenerator {

    public List<String> generate(String intent, String domain, String verbosity) {
        String v = verbosity == null ? "standard" : verbosity.toLowerCase(Locale.ROOT);
        if (!("deep".equals(v) || "ultra".equals(v))) return List.of();

        if (intent != null && intent.matches("TUTORIAL|EXPLANATION|ANALYSIS")) {
            return List.of("개요", "원리", "절차", "사례", "한계", "다음 단계");
        }
        return List.of("개요", "세부 설명", "사례", "한계", "다음 단계");
    }
}