// src/main/java/com/example/lms/service/rag/pre/DefaultGuardrailQueryPreprocessor.java
package com.example.lms.service.rag.pre;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;




@Component("defaultGuardrailQueryPreprocessor")
public class DefaultGuardrailQueryPreprocessor implements QueryContextPreprocessor {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern MULTI_SPACES = Pattern.compile("\\s{2,}");

    // (지역우선:/* ... */; 언어:/* ... */) 같은 괄호 메타 제거
    private static final Pattern PAREN_META =
            Pattern.compile("\\((?i)\\s*(지역우선|지역|location)\\s*[:：].*?\\)|\\((?i)\\s*(언어|language)\\s*[:：].*?\\)", Pattern.UNICODE_CASE);
    // “언어: ko”, “language: en” 라벨 제거
    private static final Pattern LANG_LABEL =
            Pattern.compile("(?i)\\b(언어|language)\\s*[:：]\\s*[a-z\\-]+\\b");
    private static final Pattern DOMAIN_SCOPE_PREFIX =
            Pattern.compile("(?i)^\\s*(site\\s+)?\\S+\\s+ac\\s+kr\\b");

    /**
     * Pattern matching a leading vertical bar '|' and any following whitespace.
     * Upstream transformations (e.g. some query planners) may prepend a pipe
     * character as a special marker.  Leaving it in place leads to malformed
     * search queries (encoded as %7C in URLs) which in turn causes unrelated
     * results to be returned.  This pattern removes one or more leading bars
     * and any whitespace immediately following them.
     */
    private static final Pattern LEADING_BAR = Pattern.compile("^\\|+\\s*");

    @Override
    public String enrich(String input) {
        if (input == null) return "";
        String s = input.trim();
        s = CONTROL.matcher(s).replaceAll("");
        // Remove parenthesised meta such as "(지역우선: /* ... */)" or "(언어: /* ... */)"
        s = PAREN_META.matcher(s).replaceAll("");
        // Remove language labels such as "언어: ko" or "language: en"
        s = LANG_LABEL.matcher(s).replaceAll("");
        // "검색어: /* ... */", "/* ... */ 찾아봐/검색해줘" 등 군더더기 제거
        s = s.replaceFirst("^\\s*검색어\\s*[:：]\\s*", "");
        s = s.replaceAll("\\s*(찾아봐|검색해줘|해줘|해주세요|해줄래)\\s*$", "");
        // 도메인 스코프 강제 프리픽스 제거 (검색 편향 방지)
        s = DOMAIN_SCOPE_PREFIX.matcher(s).replaceFirst("");
        // Strip any leading '|' characters left by upstream planners.  Without this
        // cleanup the pipe survives URL encoding and pollutes the query sent to
        // the search provider (e.g. %7C/* ... */).  Removing it restores the intended
        // query text.
        s = LEADING_BAR.matcher(s).replaceFirst("");
        s = MULTI_SPACES.matcher(s).replaceAll(" ");
        return s;
    }
}