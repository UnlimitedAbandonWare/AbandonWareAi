// src/main/java/com/example/lms/service/rag/subject/SubjectResolver.java
package com.example.lms.service.rag.subject;

import org.springframework.stereotype.Component;

@Component
public class SubjectResolver {
    public record Subject(String primaryKo, String aliasEn) {}

    /** 매우 경량: 큰따옴표/한글+영문 혼합 힌트 우선 추출 */
    public Subject resolve(String query) {
        if (query == null) return new Subject("", "");
        String q = query.trim();
        String en = "";
        var m = java.util.regex.Pattern.compile("\"([^\"]{2,})\"").matcher(q);
        if (m.find()) en = m.group(1).trim();

        // 한글 주어: 공백 기준 첫 토큰(2자 이상) 추정
        String ko = q.replaceAll("[\"“”`']", "").split("\\s+")[0];
        if (ko != null && ko.length() < 2) ko = "";
        return new Subject(ko == null ? "" : ko, en == null ? "" : en);
    }
}
