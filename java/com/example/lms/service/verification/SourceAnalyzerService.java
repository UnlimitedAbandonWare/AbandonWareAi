 package com.example.lms.service.verification;

import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.rag.auth.AuthorityScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 컨텍스트에 포함된 URL들의 출처 신뢰도를 집계/판정.
 * AuthorityScorer의 가중치를 이용해 OFFICIAL/COMMUNITY/CONFLICTING 등을 구분.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceAnalyzerService {
    private final AuthorityScorer authorityScorer;

    public SourceCredibility analyze(String question, String context) {
        List<String> urls = extractUrls(context);
        if (urls.isEmpty()) return SourceCredibility.UNKNOWN;

        int total = 0, high = 0, wiki = 0, low = 0;
        for (String u : urls) {
            double w;
            try { w = authorityScorer.weightFor(u); }
            catch (Exception e) { w = 0.5; }
            total++;
            if (w >= 0.90)      high++;       // 공식/고신뢰
            else if (w >= 0.70) wiki++;       // 위키권
            else if (w <= 0.40) low++;        // 커뮤니티/저신뢰
        }
        double highR = total == 0 ? 0 : (double) high / total;
        double wikiR = total == 0 ? 0 : (double) wiki / total;
        double lowR  = total == 0 ? 0 : (double) low  / total;
        boolean conflicting = (highR >= 0.25) && (lowR >= 0.25);

        if (highR >= 0.50) return SourceCredibility.OFFICIAL;
        if (wikiR >= 0.50 && highR >= 0.10) return SourceCredibility.RELIABLE_WIKI;
        if (lowR >= 0.50 && highR < 0.10)  return SourceCredibility.FAN_MADE_SPECULATION;
        if (conflicting)                   return SourceCredibility.CONFLICTING;
        return SourceCredibility.UNKNOWN;
    }

    /** HTML 스니펫에서 URL 추출 (href 및 bare http 링크) */
    static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher a = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text);
        while (a.find()) out.add(a.group(1));
        Matcher b = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (b.find()) out.add(b.group(1));
        return new ArrayList<>(out);
    }
}