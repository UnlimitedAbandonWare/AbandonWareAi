// src/main/java/com/example/lms/service/rag/pre/DefaultGuardrailQueryPreprocessor.java
package com.example.lms.service.rag.pre;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component("defaultGuardrailQueryPreprocessor")
public class DefaultGuardrailQueryPreprocessor implements QueryContextPreprocessor {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern MULTI_SPACES = Pattern.compile("\\s{2,}");
    private static final Pattern DOMAIN_SCOPE_PREFIX =
            Pattern.compile("(?i)^\\s*(site\\s+)?\\S+\\s+ac\\s+kr\\b");

    @Override
    public String enrich(String input) {
        if (input == null) return "";
        String s = input.trim();
        s = CONTROL.matcher(s).replaceAll("");
        // "검색어: …", "… 찾아봐/검색해줘" 등 군더더기 제거
        s = s.replaceFirst("^\\s*검색어\\s*[:：]\\s*", "");
        s = s.replaceAll("\\s*(찾아봐|검색해줘|해줘|해주세요|해줄래)\\s*$", "");
        // 도메인 스코프 강제 프리픽스 제거 (검색 편향 방지)
        s = DOMAIN_SCOPE_PREFIX.matcher(s).replaceFirst("");
        s = MULTI_SPACES.matcher(s).replaceAll(" ");
        return s;
    }
}
