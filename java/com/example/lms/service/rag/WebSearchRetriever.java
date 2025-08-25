package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;             /* 🔴 NEW */
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.filter.EducationDocClassifier;
@Slf4j
@RequiredArgsConstructor
@org.springframework.stereotype.Component
public class WebSearchRetriever implements ContentRetriever {
    private final NaverSearchService searchSvc;
    // 스프링 프로퍼티로 주입(생성자 주입의 int 빈 문제 회피)
    @org.springframework.beans.factory.annotation.Value("${rag.search.top-k:5}")
    private int topK;
    private final com.example.lms.service.rag.extract.PageContentScraper pageScraper;
    private static final int MIN_SNIPPETS = 2;
    //  도메인 신뢰도 점수로 정렬 가중
    private final com.example.lms.service.rag.auth.AuthorityScorer authorityScorer;
    // 범용 판정기는 주입받아 도메인별로 동작하도록 한다.
    private final GenericDocClassifier genericClassifier;
    // 질의 도메인 추정기
    private final com.example.lms.service.rag.detector.GameDomainDetector domainDetector;
    // 교육 토픽 분류기: 교육 도메인일 때 스니펫 필터링에 사용된다.
    private final com.example.lms.service.rag.filter.EducationDocClassifier educationClassifier;
    private static final Pattern META_TAG = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern TIME_TAG = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
    /* 🔵 봇/캡차 페이지 힌트 */
    /* DuckDuckGo 등에서 반환되는 캡차/봇 차단 힌트 제거용 */
    private static final Pattern CAPTCHA_HINT = Pattern.compile(
            "(?i)(captcha|are you (a )?robot|unusual\\s*traffic|verify you are human|duckduckgo\\.com/captcha|bots\\s*use\\s*duckduckgo)");


    private static String normalize(String raw) {        /* 🔴 NEW */
        if (raw == null) return "";

        String s = META_TAG.matcher(raw).replaceAll("");
        s = TIME_TAG.matcher(s).replaceAll("");
        return s.replace("\n", " ").trim();
    }

    /**
     * Extract a version token from the query string.  A version is defined
     * as two numeric components separated by a dot or middot character.  If
     * no such token is present, {@code null} is returned.
     *
     * @param q the query text
     * @return the extracted version (e.g. "5.8") or null
     */
    private static String extractVersion(String q) {
        if (q == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)[\\.·](\\d+)").matcher(q);
        return m.find() ? (m.group(1) + "." + m.group(2)) : null;
    }

    /**
     * Build a regex that matches the exact version token in text.  Dots in
     * the version are replaced with a character class that matches dot or
     * middot to handle variations in punctuation.  Anchors ensure that
     * longer numbers containing the version as a substring are not falsely
     * matched.
     *
     * @param v the version string (e.g. "5.8")
     * @return a compiled regex pattern matching the exact token
     */
    private static java.util.regex.Pattern versionRegex(String v) {
        String core = v.replace(".", "[\\.·\\s]");
        return java.util.regex.Pattern.compile("(?<!\\d)" + core + "(?!\\d)");
    }

    /* ✅ 선호 도메인: 제거가 아닌 '우선 정렬'만 수행 */
    private static final List<String> PREFERRED = List.of(
            // 공식/권위
            "genshin.hoyoverse.com", "hoyoverse.com", "hoyolab.com",
            "wikipedia.org", "eulji.ac.kr", "ac.kr", "go.kr",
            // 한국 커뮤니티·블로그(삭제 X, 단지 후순위)
            "namu.wiki", "blog.naver.com"
    );
    private static boolean containsPreferred(String s) {
        return PREFERRED.stream().anyMatch(s::contains);
    }

    @Override
    public List<Content> retrieve(Query query) {
        String normalized = normalize(query != null ? query.text() : "");
        // 쿼리 도메인 추정: null 가능성을 고려하여 GENERAL 기본값 사용
        String domain = domainDetector != null ? domainDetector.detect(normalized) : "GENERAL";
        boolean isGeneral = "GENERAL".equalsIgnoreCase(domain);

        // Extract a version token from the query.  When present, enforce that
        // each snippet contains the exact version.  This helps prevent
        // contamination from neighbouring versions (e.g. 5.7 or 5.9) when the
        // user asks about a specific patch.
        String ver = extractVersion(normalized);
        java.util.regex.Pattern must = (ver != null) ? versionRegex(ver) : null;
        // 1) 1차 수집: topK*2 → 중복/정렬 후 topK
        List<String> first = searchSvc.searchSnippets(normalized, Math.max(topK, 1) * 2)
                .stream()
                .filter(s -> !CAPTCHA_HINT.matcher(s).find())  // 🔒 캡차 노이즈 컷
                .filter(s -> must == null || must.matcher(s).find()) // enforce version token when necessary
                // cut tag pages early (/tag/ or ?tag=)
                .filter(s -> {
                    String url = extractUrl(s);
                    if (url == null) return true;
                    String lower = url.toLowerCase();
                    return !(lower.contains("/tag/") || lower.contains("?tag="));
                })
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] first raw={} (q='{}')", first.size(), normalized);
        }
        // 선호+ 도메인  Authority 가중 정렬(삭제 아님). 범용 페널티는 GENERAL/EDUCATION 도메인에서는 제거
        List<String> ranked = first.stream()
                .distinct()
                .sorted((a, b) -> {
                    double aw = authorityScorer.weightFor(extractUrl(a))
                               - (isGeneral ? 0.0 : genericClassifier.penalty(a, domain));
                    double bw = authorityScorer.weightFor(extractUrl(b))
                               - (isGeneral ? 0.0 : genericClassifier.penalty(b, domain));
                    int cmp = Double.compare(bw, aw); // high first (penalty 반영)
                    if (cmp != 0) return cmp;
                    // 동률이면 선호 도메인 우선
                    return Boolean.compare(containsPreferred(b), containsPreferred(a));
                })
                .limit(topK)
                .toList();
        // 범용 스니펫 컷: 도메인 특화(예: GENSHIN/EDU)에서만 적용
        if (!isGeneral) {
            ranked = ranked.stream()
                    .filter(s -> !genericClassifier.isGenericSnippet(s, domain))
                    .limit(topK)
                    .toList();
        }

        // 2) 폴백: 지나친 공손어/호칭 정리
        List<String> fallback = ranked.size() >= MIN_SNIPPETS ? List.of()
                : searchSvc.searchSnippets(normalized.replace("교수님", "교수").replace("님",""), topK);

        List<String> finalSnippets = java.util.stream.Stream.of(ranked, fallback)
                .flatMap(java.util.Collection::stream)
                .distinct()
                .limit(topK)
                .toList();

        // If the detected domain is EDUCATION, apply an education topic filter
        // to remove snippets that are unrelated to education/academy topics.  This
        // leverages the EducationDocClassifier to detect whether a snippet is
        // genuinely about education.  Without this filter generic or noise
        // snippets from pet or automotive sites may contaminate the retrieval.
        if ("EDUCATION".equalsIgnoreCase(domain) && educationClassifier != null) {
            finalSnippets = finalSnippets.stream()
                    .filter(s -> {
                        try {
                            return educationClassifier.isEducation(s);
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .limit(topK)
                    .toList();
        }

        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] selected={} (topK={})", finalSnippets.size(), topK);
        }
        // 3) 각 결과의 URL 본문을 읽어 ‘질문-유사도’로 핵심 문단 추출
        java.util.List<Content> out = new java.util.ArrayList<>();
        for (String s : finalSnippets) {
            String url = extractUrl(s);   // ⬅️ 없던 util 메서드 추가(아래)
            if (url == null || CAPTCHA_HINT.matcher(s).find()) { // 🔒 의심 라인 스킵
                out.add(Content.from(s)); // URL 없음 → 기존 스니펫 사용
                continue;
            }
            try {
                String body = pageScraper.fetchText(url, /*timeoutMs*/6000);
                // SnippetPruner는 (String, String) 시그니처만 존재 → 단일 결과로 처리
                // 🔵 우리 쪽 간단 딥 스니펫 추출(임베딩 없이 키워드/길이 기반)
                String picked = pickByHeuristic(query.text(), body, 480);
                if (picked == null || picked.isBlank()) {
                    out.add(Content.from(s));
                } else {
                    out.add(Content.from(picked + "\n\n[출처] " + url));
                }
            } catch (Exception e) {
                log.debug("[WebSearchRetriever] scrape fail {} → fallback snippet", url);
                out.add(Content.from(s));
            }
        }
        return out.stream().limit(topK).toList();
    }

    // ── NEW: 스니펫 문자열에서 URL을 뽑아내는 간단 파서(프로젝트 전반 동일 규칙과 일치)
    private static String extractUrl(String text) {
        if (text == null) return null;
        int a = text.indexOf("href=\"");
        if (a >= 0) {
            int s = a + 6, e = text.indexOf('"', s);
            if (e > s) return text.substring(s, e);
        }
        int http = text.indexOf("http");
        if (http >= 0) {
            int sp = text.indexOf(' ', http);
            return sp > http ? text.substring(http, sp) : text.substring(http);
        }
        return null;
    }
    // ── NEW: SnippetPruner 없이도 동작하는 경량 딥 스니펫 추출기
    private static String pickByHeuristic(String q, String body, int maxLen) {
        if (body == null || body.isBlank()) return "";
        if (q == null) q = "";
        String[] toks = q.toLowerCase().split("\\s+");
        String[] sents = body.split("(?<=[\\.\\?\\!。！？])\\s+");
        String best = "";
        int bestScore = -1;
        for (String s : sents) {
            if (s == null || s.isBlank()) continue;
            String ls = s.toLowerCase();
            int score = 0;
            for (String t : toks) {
                if (t.isBlank()) continue;
                if (ls.contains(t)) score += 2;      // 질의 토큰 포함 가중
            }
            score += Math.min(s.length(), 300) / 60;   // 문장 길이 가중(너무 짧은 문장 패널티)
            if (score > bestScore) { bestScore = score; best = s.trim(); }
        }
        if (best.isEmpty()) {
            best = body.length() > maxLen ? body.substring(0, maxLen) : body;
        } else if (best.length() > maxLen) {
            best = best.substring(0, maxLen) + "…";
        }
        return best;
    }
}
