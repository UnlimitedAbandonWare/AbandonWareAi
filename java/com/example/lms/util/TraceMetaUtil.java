package com.example.lms.util;

import org.springframework.util.StringUtils;



public final class TraceMetaUtil {

    private TraceMetaUtil() {}

    // 로그에서 사용하던 프리픽스
    public static final String TRACE_META_PREFIX = "⎔TRACE⎔";

    /** system + ⎔TRACE⎔ 시작 여부 */
    public static boolean isTraceSystem(String role, String content) {
        if (!StringUtils.hasText(content)) return false;
        return content.startsWith(TRACE_META_PREFIX);
    }

    /** ⎔TRACE⎔ 프리픽스를 제거한 실제 HTML 반환 */
    public static String stripTracePrefix(String content) {
        if (content == null) return "";
        return content.startsWith(TRACE_META_PREFIX)
                ? content.substring(TRACE_META_PREFIX.length()).trim()
                : content;
    }

    /** assistant 본문 뒤에 검색기록 HTML을 합쳐서 반환 */
    public static String mergeHtml(String assistant, String traceHtml) {
        String base = (assistant == null) ? "" : assistant;
        String extra = (traceHtml == null) ? "" : traceHtml;
        if (!StringUtils.hasText(base)) return extra;
        if (!StringUtils.hasText(extra)) return base;
        // 줄바꿈 2개로 시각적 구분
        return base + "\n\n" + extra;
    }
}