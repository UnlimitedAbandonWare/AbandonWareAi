package com.example.lms.service.rag.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Component
public class PageContentScraper {

    /**
     * URL의 HTML을 받아 본문 텍스트만 최대 길이로 깔끔히 반환.
     */
    public String fetchText(String url, int timeoutMs) throws Exception {
        Document doc = Jsoup.connect(url)
                .ignoreContentType(true)
                .userAgent("Mozilla/5.0 (RAG/DeepSnippet)")
                .timeout(Math.max(3000, timeoutMs))
                .get();

        // 메타/스크립트 제거
        doc.select("script,noscript,style,header,footer,nav,aside").remove();

        // 본문 후보(블로그/커뮤니티 가중)
        Elements bodies = doc.select("article, main, #content, .post, .entry-content, .article, .post-body, .content");
        if (bodies.isEmpty()) bodies = doc.body().children();

        String text = bodies.stream()
                .map(Element::text)
                .collect(Collectors.joining("\n"))
                .replace("\u00A0", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        // 안전 길이 제한
        if (text.length() > 24000) {
            text = new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            text = text.substring(0, 24000);
        }
        return text;
    }
}
