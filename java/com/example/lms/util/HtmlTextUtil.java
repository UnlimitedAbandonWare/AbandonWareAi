package com.example.lms.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 스니펫을 plain text로 변환하는 유틸리티.
 * Guard/Evidence 경로에서 HTML 누수 방지용.
 */
public final class HtmlTextUtil {

    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern A_TEXT = Pattern.compile("<a\\b[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "^(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/.*)?$");

    private HtmlTextUtil() {
    }

    /** anchor 태그에서 href 추출 */
    public static String extractFirstHref(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Matcher m = HREF.matcher(html);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** anchor 태그에서 inner text 추출 */
    public static String extractAnchorText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Matcher m = A_TEXT.matcher(html);
            if (m.find()) {
                return stripAndCollapse(m.group(1));
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** "- <a href=...>TITLE</a>: DESC" 형식에서 ": DESC" 부분만 추출 */
    public static String afterAnchor(String html) {
        if (html == null) {
            return null;
        }
        String s = html;
        try {
            int aEnd = s.toLowerCase().indexOf("</a>");
            if (aEnd >= 0) {
                int colon = s.indexOf(':', aEnd);
                if (colon >= 0 && colon + 1 < s.length()) {
                    return s.substring(colon + 1);
                }
                if (aEnd + 4 < s.length()) {
                    return s.substring(aEnd + 4);
                }
            }
        } catch (Exception ignore) {
        }
        return s;
    }

    /** HTML 태그 제거 + 공백 정리 */
    public static String stripAndCollapse(String html) {
        if (html == null) {
            return "";
        }
        String s = html;
        try {
            s = TAGS.matcher(s).replaceAll(" ");
        } catch (Exception ignore) {
        }
        s = decodeEntities(s);
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** 기본 HTML 엔티티 디코딩 */
    public static String decodeEntities(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    /** URL 정규화: //example.com → https://example.com */
    public static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty()) {
            return url;
        }
        // 따옴표 제거
        if ((url.startsWith("\"") && url.endsWith("\"")) || (url.startsWith("'") && url.endsWith("'"))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        if (BARE_DOMAIN.matcher(url).matches()) {
            return "https://" + url;
        }
        return url;
    }
}
