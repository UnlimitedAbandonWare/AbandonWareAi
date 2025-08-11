package com.example.lms.service.verification;

import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.rag.auth.AuthorityScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 출처 신뢰도 분석 서비스 (통합).
 *
 * - 1차: 간단 키워드 휴리스틱(충돌/팬메이드/공식 도메인)으로 빠른 판정
 * - 2차: URL이 있으면 AuthorityScorer 가중치 분포로 세밀 판정
 * - AuthorityScorer 빈이 없거나 URL이 없으면 휴리스틱만 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAnalyzerService {

    private final ObjectProvider<AuthorityScorer> authorityScorerProvider;

    // --- 휴리스틱 패턴 ---
    private static final Pattern FAN =
            Pattern.compile("(?i)(fan[- ]?made|추측|루머|rumor|fandom\\.com)");
    private static final Pattern OFFICIAL_DOMAIN =
            Pattern.compile("(?i)(hoyoverse\\.com|hoyolab\\.com|genshin\\.hoyoverse\\.com)");
    private static final Pattern CONFLICT =
            Pattern.compile("(?i)(상충|모순|conflict|서로\\s*다르)");

    // --- 가중치 임계값 (조정 가능) ---
    private static final double HIGH_T = 0.90;         // 공식/고신뢰 컷
    private static final double WIKI_T = 0.70;         // 위키권 컷
    private static final double LOW_T  = 0.40;         // 저신뢰 컷

    private static final double OFFICIAL_RATIO = 0.50; // high 비율 ≥ 50%
    private static final double WIKI_RATIO     = 0.50; // wiki 비율 ≥ 50%
    private static final double WIKI_HIGH_MIN  = 0.10; // wiki 판정 시 high 최소 비율
    private static final double FAN_LOW_RATIO  = 0.50; // low 비율 ≥ 50%
    private static final double FAN_HIGH_MAX   = 0.10; // fan 판정 시 high 최대 비율
    private static final double CONFLICT_MIN   = 0.25; // high/low 동시 존재 비율

    public SourceCredibility analyze(String question, String context) {
        if (context == null || context.isBlank()) {
            return SourceCredibility.UNKNOWN;
        }

        // 1) 텍스트 레벨 즉시 판정
        if (CONFLICT.matcher(context).find()) {
            return SourceCredibility.CONFLICTING;
        }

        // 2) URL 기반 정밀 판정 (가능하면 우선 시도)
        List<String> urls = extractUrls(context);
        AuthorityScorer scorer = authorityScorerProvider.getIfAvailable();
        if (scorer != null && !urls.isEmpty()) {
            try {
                SourceCredibility byDist = judgeByWeightDistribution(scorer, urls);
                if (byDist != SourceCredibility.UNKNOWN) {
                    return byDist;
                }
            } catch (Exception e) {
                log.debug("AuthorityScorer 평가 실패, 휴리스틱으로 폴백: {}", e.getMessage());
            }
        }

        // 3) 휴리스틱 폴백
        if (OFFICIAL_DOMAIN.matcher(context).find()) {
            return SourceCredibility.OFFICIAL;
        }
        if (FAN.matcher(context).find()) {
            return SourceCredibility.FAN_MADE_SPECULATION;
        }

        return SourceCredibility.UNKNOWN;
    }

    private SourceCredibility judgeByWeightDistribution(AuthorityScorer scorer, List<String> urls) {
        int total = 0, high = 0, wiki = 0, low = 0;

        for (String u : urls) {
            double w;
            try {
                w = scorer.weightFor(u);
            } catch (Exception e) {
                w = 0.5; // 중립 가중치
            }
            total++;
            if (w >= HIGH_T)       high++;
            else if (w >= WIKI_T)  wiki++;
            else if (w <= LOW_T)   low++;
        }

        if (total == 0) return SourceCredibility.UNKNOWN;

        double highR = (double) high / total;
        double wikiR = (double) wiki / total;
        double lowR  = (double) low  / total;

        boolean conflicting = (highR >= CONFLICT_MIN) && (lowR >= CONFLICT_MIN);

        if (highR >= OFFICIAL_RATIO)                           return SourceCredibility.OFFICIAL;
        if (wikiR >= WIKI_RATIO && highR >= WIKI_HIGH_MIN)     return SourceCredibility.RELIABLE_WIKI;
        if (lowR  >= FAN_LOW_RATIO && highR < FAN_HIGH_MAX)    return SourceCredibility.FAN_MADE_SPECULATION;
        if (conflicting)                                       return SourceCredibility.CONFLICTING;

        return SourceCredibility.UNKNOWN;
    }

    /** HTML/텍스트에서 URL 추출 (href 및 bare http 링크) */
    public static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher a = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text);
        while (a.find()) out.add(a.group(1));
        Matcher b = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (b.find()) out.add(b.group(1));
        return new ArrayList<>(out);
    }
}