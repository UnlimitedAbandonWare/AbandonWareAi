// src/main/java/com/example/lms/service/rag/filter/GenericDocClassifier.java
package com.example.lms.service.rag.filter;

import java.util.regex.Pattern;

public class GenericDocClassifier {
    private static final Pattern GENERIC =
            Pattern.compile("(모든\\s*캐릭터|전\\s*캐릭터|최강\\s*파티|티어\\s*등급|총정리|리세마라|가이드\\s*총집합)",
                    Pattern.CASE_INSENSITIVE);

    public boolean isGenericSnippet(String snippetLine) {
        return snippetLine != null && GENERIC.matcher(snippetLine).find();
    }

    public boolean isGenericText(String text) {
        return text != null && GENERIC.matcher(text).find();
    }

    /** 랭킹에 쓸 가벼운 페널티(0.0~0.5 권장) */
    public double penalty(String text) {
        return isGenericText(text) ? 0.25 : 0.0;
    }
}
