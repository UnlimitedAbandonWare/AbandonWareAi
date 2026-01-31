package com.example.lms.service.rag.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;




@Component
public class PageContentScraper {
    @Value("${search.budget.per-page-ms:3500}")
    private int defaultPerPageMs;

    /**
     * URL의 HTML을 받아 본문 텍스트만 최대 길이로 깔끔히 반환.
     */
    public String fetchText(String url) {
        return fetchText(url, defaultPerPageMs);
    }

    /**
     * Fetches the text contents of the given URL within a configurable timeout.
     * When the timeout is reached or any error occurs, this method returns
     * {@code null} to signal that the caller should continue gracefully.
     *
     * @param url       the page URL to scrape
     * @param perPageMs the timeout in milliseconds applied to the request
     * @return the extracted and trimmed text, or {@code null} if the page could not be fetched
     */
    public String fetchText(String url, int perPageMs) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; AbandonWareBot/1.0)")
                    .timeout(Math.max(1000, perPageMs))
                    .get();
            String text = doc.text();
            return (text != null) ? text.strip() : null;
        } catch (Exception e) {
            // Allow partial success by returning null on any failure
            return null;
        }
    }
}