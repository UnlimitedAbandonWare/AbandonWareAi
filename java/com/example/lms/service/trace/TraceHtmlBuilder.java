package com.example.lms.service.trace;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;




/**
 * Builder for generating HTML fragments describing the search trace.  Unlike the
 * legacy implementation in {@link NaverSearchService}, this class accepts the
 * post-curation top-K list of contents and renders those as the primary
 * snippet list.  Raw snippets (pre-curation) may optionally be supplied and
 * will only be shown in a collapsed "Debug only" section.  When no curated
 * results are provided the raw snippets are treated as the curated list.
 */
@Component
public class TraceHtmlBuilder {

    /**
     * Build the trace panel HTML from the raw trace and a curated result set.
     *
     * @param rawTrace      the low level search trace as returned from the web
     *                      search service
     * @param curatedTopK   the list of curated contents (post rerank and
     *                      filtering).  May be {@code null}, in which case the
     *                      raw snippets will be used for the primary snippet
     *                      panel
     * @param rawSnippets   raw snippets from the search provider.  When
     *                      provided these will be shown in a collapsed details
     *                      section for debugging purposes
     * @return a HTML fragment suitable for embedding in the search trace panel
     */
    public String buildFromCurated(NaverSearchService.SearchTrace rawTrace,
                                   List<Content> curatedTopK,
                                   List<String> rawSnippets) {
        if (rawTrace == null) {
            return "";
        }
        // Determine which snippets to display in the main panel.  Prefer the
        // curated list when available; otherwise fall back to the raw
        // snippets.  When both are null an empty list is used.
        List<String> mainSnippets;
        if (curatedTopK != null && !curatedTopK.isEmpty()) {
            mainSnippets = curatedTopK.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        } else if (rawSnippets != null) {
            mainSnippets = rawSnippets;
        } else {
            mainSnippets = List.of();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"search-trace\"><summary>🔎 검색 과정 (")
                .append(mainSnippets.size()).append("개 스니펫) · ")
                .append(rawTrace.totalMs).append("ms</summary>");
        sb.append("<div class=\"trace-body\">");
        sb.append("<div class=\"trace-meta small text-muted\">")
                .append("도메인필터 ").append(rawTrace.domainFilterEnabled ? "ON" : "OFF")
                .append(" · 키워드필터 ").append(rawTrace.keywordFilterEnabled ? "ON" : "OFF");
        if (rawTrace.suffixApplied != null) {
            sb.append(" · 접미사: ").append(rawTrace.suffixApplied);
        }
        sb.append("</div>");
        sb.append("<ol class=\"trace-steps\">");
        for (NaverSearchService.SearchStep step : rawTrace.steps) {
            sb.append("<li><code>").append(escape(step.query)).append("</code>")
                    .append(" → 응답 ").append(step.returned)
                    .append("건, 필터 후 ").append(step.afterFilter)
                    .append("건 (").append(step.tookMs).append("ms)")
                    .append("</li>");
        }
        sb.append("</ol>");
        // Render the curated or fallback snippet list
        if (!mainSnippets.isEmpty()) {
            sb.append("<div class=\"trace-snippets\"><ul>");
            for (String line : mainSnippets) {
                sb.append("<li>").append(line).append("</li>");
            }
            sb.append("</ul></div>");
        }
        // Render raw snippets in a collapsed details block when they differ
        if (rawSnippets != null && !rawSnippets.isEmpty() && (curatedTopK != null && !curatedTopK.isEmpty())) {
            sb.append("<details class=\"trace-raw\"><summary>Debug only</summary><div><ul>");
            for (String line : rawSnippets) {
                sb.append("<li>").append(line).append("</li>");
            }
            sb.append("</ul></div></details>");
        }
        sb.append("</div></details>");
        return sb.toString();
    }

    /**
     * Escape angle brackets and ampersands to avoid HTML injection.  This
     * method intentionally does not escape quotes because the output is not
     * used within an attribute context.
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}