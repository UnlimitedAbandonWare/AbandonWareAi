package com.example.lms.gptsearch.web.provider;

import com.example.lms.gptsearch.web.MultiWebSearch;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 공통 리플렉션/파싱 헬퍼. 각 Web 검색 어댑터가 상속하여 중복 코드를 줄입니다.
 */
public abstract class BaseWebProviderAdapter implements MultiWebSearch.Provider {

    protected static List<?> asList(Object v) {
        return (v instanceof List<?> l) ? l : java.util.List.of();
    }

    /**
     * 대상 객체에서 여러 접근자 중 첫 번째 성공한 문자열을 반환합니다.
     */
    // Accept an array of method names instead of varargs to avoid the triple-dot
    // token in the signature.  Using a simple array eliminates the variable
    // length argument syntax while preserving functionality.
    protected static String callString(Object o, String[] getters) {
        for (String g : getters) {
            try {
                var m = o.getClass().getMethod(g);
                Object v = m.invoke(o);
                if (v != null) return String.valueOf(v);
            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * 대상 객체에서 여러 접근자 중 첫 번째 성공한 날짜를 파싱합니다. Instant 인스턴스나 문자열을 처리합니다.
     */
    // Accept an array of method names instead of varargs to avoid the triple-dot
    // token in the signature.  Using a simple array eliminates the variable
    // length argument syntax while preserving functionality.
    protected static Instant parsePublished(Object o, String[] getters) {
        for (String g : getters) {
            try {
                var m = o.getClass().getMethod(g);
                Object v = m.invoke(o);
                if (v == null) continue;
                if (v instanceof Instant inst) return inst;
                Instant t = tryParseDate(String.valueOf(v));
                if (t != null) return t;
            } catch (Exception ignored) {}
        }
        return null;
    }

    protected static Instant tryParseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception ignored) {}
        try { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (Exception ignored) {}
        try { return ZonedDateTime.parse(s + "T00:00:00Z").toInstant(); } catch (Exception ignored) {}
        return null;
    }
}