package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.service.NaverSearchService;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;




/**
 * A concrete {@link WebSearchProvider} that delegates to the existing
 * {@link NaverSearchService} for performing web searches.  The service
 * returns raw HTML snippets; this provider extracts the URL, title,
 * plain-text snippet and an optional timestamp from each result.  When
 * errors occur the provider defers to the base class to return an empty
 * {@link WebSearchResult} so that other providers can be consulted.
 */
@Component
public class NaverProvider extends AbstractWebSearchProvider {

    private final NaverSearchService naver;

    public NaverProvider(NaverSearchService naver) {
        this.naver = naver;
    }

    @Override
    public ProviderId id() {
        return ProviderId.NAVER;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery q) {
        // Delegate to the Naver service to fetch raw snippets.  We use the
        // synchronous API here because the provider interface is blocking.
        List<String> snippets = naver.searchSnippets(q.getQuery(), q.getTopK());
        List<WebDocument> docs = snippets.stream()
                .map(s -> {
                    String url = extractUrl(s);
                    String title = extractTitle(s);
                    String plain = stripTags(s);
                    Instant ts = extractTimestamp(s);
                    return new WebDocument(url, title, plain, null, ts);
                })
                .collect(Collectors.toList());
        return new WebSearchResult(id().name(), docs);
    }

    /**
     * Extract the first hyperlink from the snippet.  The raw snippet is
     * expected to contain an anchor tag of the form <a href="/* ... *&#47;">text</a>.
     * When no anchor is found a best effort is made to locate an HTTP URL.
     */
    private static String extractUrl(String snippet) {
        if (snippet == null) {
            return null;
        }
        int href = snippet.indexOf("href=\"");
        if (href >= 0) {
            int start = href + 6;
            int end = snippet.indexOf('"', start);
            if (end > start) {
                return snippet.substring(start, end);
            }
        }
        int http = snippet.indexOf("http");
        if (http >= 0) {
            int space = snippet.indexOf(' ', http);
            return space > http ? snippet.substring(http, space) : snippet.substring(http);
        }
        return null;
    }

    /**
     * Attempt to extract a title from within the first anchor tag.  The
     * contents of the anchor are returned with all HTML tags removed.  If
     * no anchor exists, {@code null} is returned.
     */
    private static String extractTitle(String snippet) {
        if (snippet == null) {
            return null;
        }
        int aStart = snippet.indexOf("<a");
        if (aStart >= 0) {
            int textStart = snippet.indexOf('>', aStart);
            if (textStart >= 0) {
                int textEnd = snippet.indexOf("</a>", textStart);
                if (textEnd > textStart) {
                    String inner = snippet.substring(textStart + 1, textEnd);
                    return stripTags(inner);
                }
            }
        }
        return null;
    }

    /**
     * Remove basic HTML tags and decode common entities.  This simple
     * implementation strips tags by regex; for production consider using
     * Jsoup or another HTML parser.
     */
    private static String stripTags(String html) {
        if (html == null) {
            return null;
        }
        // Remove tags
        String s = html.replaceAll("<[^>]+>", "");
        // Decode a few common entities manually
        s = s.replace("&nbsp;", " ");
        s = s.replace("&amp;", "&");
        s = s.replace("&lt;", "<");
        s = s.replace("&gt;", ">");
        return s.trim();
    }

    /**
     * Detect a publication date within the snippet.  Accepts patterns of the
     * form YYYY.MM.DD, YYYY-MM-DD or YYYY/MM/DD.  When a match is found the
     * date is returned as an {@link Instant} at midnight UTC.  When no date
     * is found this returns {@code null}.
     */
    private static Instant extractTimestamp(String snippet) {
        if (snippet == null) {
            return null;
        }
        Pattern p = Pattern.compile("(\\d{4})[\\./-](\\d{1,2})[\\./-](\\d{1,2})");
        Matcher m = p.matcher(snippet);
        if (m.find()) {
            try {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                LocalDate date = LocalDate.of(year, month, day);
                return date.atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // Ignore parse errors and fall through to return null
            }
        }
        return null;
    }
}