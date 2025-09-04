package com.example.lms.service.correction;

import java.util.*;
import java.util.regex.Pattern;

/** Minimal Korean‑first preprocessor to avoid over‑correction. */
public final class KoreanPreprocessor {
    private KoreanPreprocessor() {}
    private static final Map<String,String> SYN = Map.of(
        "dw아카데미", "디더블유 아카데미",
        "대전dw", "대전 디더블유",
        // 정규화 항목: '스커크' 및 흔한 오타 변형을 '스쿠로스'로 교정한다.
        "스커크", "스쿠로스",
        "스쿠르소", "스쿠로스"
    );
    public static String preprocess(String s) {
        if (s == null) return null;
        String t = s;
        // Normalize basic punctuation and spaces (keep Korean intact)
        t = t.replace(' ',' ').replaceAll("\\s+", " ").trim();
        // Light decompounding: split english+korean runs with a space
        t = t.replaceAll("(?i)([a-z]{2,})([가-힣])", "$1 $2");
        t = t.replaceAll("(?i)([가-힣])([a-z]{2,})", "$1 $2");
        // Synonym expansion (very small, conservative)
        for (var e : SYN.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        return t;
    }
    /** Return true if change magnitude is minor (avoid LLM correction). */
    public static boolean minorChange(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.replaceAll("\\s+","");
        String nb = b.replaceAll("\\s+","");
        int da = Math.abs(na.length() - nb.length());
        return da <= 2 && similarity(na, nb) >= 0.9;
    }
    private static double similarity(String x, String y) {
        int m = Math.min(x.length(), y.length());
        int same = 0;
        for (int i=0;i<m;i++) if (x.charAt(i)==y.charAt(i)) same++;
        return m==0 ? 1.0 : (double)same / m;
    }
}
