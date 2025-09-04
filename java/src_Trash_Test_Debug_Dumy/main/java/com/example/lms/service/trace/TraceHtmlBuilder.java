package com.example.lms.service.trace;

import com.example.lms.dto.LinkDto;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.NaverSearchService.SearchTrace;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builder for generating HTML fragments describing the search trace and fused link results.
 *
 * This class centralises the construction of trace panels used by the frontend to
 * display either raw search snippets or fused web/vector links.  It provides a
 * simple API to render traditional snippet-based traces via {@link #build(String, List, List)}
 * as well as combined views for fused link lists via {@link #buildCombined(String, List)}.
 */
@Component
public class TraceHtmlBuilder {

    /**
     * Build the trace panel HTML from the raw trace and a curated result set.
     *
     * @param rawTrace        the low level search trace as returned from the web
     *                        search service
     * @param curatedSnippets a list of curated snippet strings; may be {@code null}, in which case
     *                        an empty list is used
     * @return a HTML fragment suitable for embedding in the search trace panel
     */
    public String buildFromCurated(SearchTrace rawTrace,
                                   List<String> curatedSnippets) {
        if (rawTrace == null) {
            return "";
        }
        // Use the provided curated snippets as the primary snippet list; fall back to an empty list when null.
        List<String> mainSnippets = (curatedSnippets != null) ? curatedSnippets : List.of();
        // In ultra profile we hide offline/fail-soft traces entirely.  Invoke the
        // TraceCompat.offline() helper which inspects the raw trace object via
        // reflection and the snippet list to decide if a panel should be rendered.
        if (com.example.lms.service.trace.TraceCompat.offline(rawTrace, mainSnippets)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"search-trace\"><summary>🔎 검색 과정 (")
          .append(mainSnippets.size()).append("개 스니펫) · ")
          // Use TraceCompat to extract elapsed duration regardless of method name
          .append(com.example.lms.service.trace.TraceCompat.totalMs(rawTrace))
          .append("ms</summary>");
        sb.append("<div class=\"trace-body\">");
        sb.append("<div class=\"trace-meta small text-muted\">")
          .append("도메인필터 ")
          .append(com.example.lms.service.trace.TraceCompat.domainFilterEnabled(rawTrace) ? "ON" : "OFF")
          .append(" · 키워드필터 ")
          .append(com.example.lms.service.trace.TraceCompat.keywordFilterEnabled(rawTrace) ? "ON" : "OFF");
        String suffix = com.example.lms.service.trace.TraceCompat.suffixApplied(rawTrace);
        if (suffix != null) {
            sb.append(" · 접미사: ").append(escape(suffix));
        }
        sb.append("</div>");
        sb.append("<ol class=\"trace-steps\">");
        for (NaverSearchService.SearchStep step : rawTrace.steps()) {
            sb.append("<li><code>").append(escape(step.query())).append("</code>")
              .append(" → 응답 ").append(step.returned())
              .append("건, 필터 후 ").append(step.afterFilter())
              .append("건 (").append(step.tookMs()).append("ms)")
              .append("</li>");
        }
        sb.append("</ol>");
        // Render the curated snippet list when present
        if (!mainSnippets.isEmpty()) {
            sb.append("<div class=\"trace-snippets\"><ul>");
            for (String line : mainSnippets) {
                sb.append("<li>").append(escape(line)).append("</li>");
            }
            sb.append("</ul></div>");
        }
        sb.append("</div></details>");
        return sb.toString();
    }

    /**
     * Render a simple snippet-based trace given a user query and parallel lists of URLs and snippets.
     * Each URL is rendered as a hyperlink and its corresponding snippet (when provided) is shown below.
     *
     * @param query    the original user query; may be {@code null}
     * @param urls     a list of result URLs; may be {@code null} or empty
     * @param snippets a list of snippet texts aligned with the URL list; may be {@code null}
     * @return an HTML fragment representing the trace
     */
    public String build(String query, java.util.List<String> urls, java.util.List<String> snippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class='trace'><h4>Trace</h4>");
        if (query != null) {
            sb.append("<div><b>Query:</b> ").append(escape(query)).append("</div>");
        }
        if (urls != null && !urls.isEmpty()) {
            sb.append("<ol class='web'>");
            for (int i = 0; i < urls.size(); i++) {
                String u = urls.get(i);
                sb.append("<li><a href='").append(escape(u)).append("' target='_blank'>")
                  .append(escape(u)).append("</a>");
                if (snippets != null && i < snippets.size() && snippets.get(i) != null) {
                    sb.append("<div class='snippet'>").append(escape(snippets.get(i))).append("</div>");
                }
                sb.append("</li>");
            }
            sb.append("</ol>");
        }
        sb.append("</section>");
        return sb.toString();
    }

    /**
     * Render a combined view for fused (web + vector) link results.  Each link is shown with its
     * title (falling back to the URL when absent) and optional meta data (source and fused score).
     *
     * @param query      the original user query; may be {@code null}
     * @param fusedLinks the fused list of LinkDto objects; may be {@code null} or empty
     * @return an HTML fragment representing the fused link view
     */
    public String buildCombined(String query, java.util.List<LinkDto> fusedLinks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class='trace'><h4>Links</h4>");
        if (query != null) {
            sb.append("<div><b>Query:</b> ").append(escape(query)).append("</div>");
        }
        if (fusedLinks != null && !fusedLinks.isEmpty()) {
            sb.append("<ol class='fused'>");
            for (LinkDto l : fusedLinks) {
                String u = l.getUrl();
                String t = (l.getTitle() != null) ? l.getTitle() : u;
                sb.append("<li>");
                if (u != null) {
                    sb.append("<a href='").append(escape(u)).append("' target='_blank'>").append(escape(t)).append("</a>");
                } else {
                    sb.append(escape(t));
                }
                if (l.getSource() != null || l.getScore() != null) {
                    sb.append(" <span class='meta'>[");
                    if (l.getSource() != null) sb.append(escape(l.getSource()));
                    if (l.getSource() != null && l.getScore() != null) sb.append(" ");
                    if (l.getScore() != null) sb.append(String.format(java.util.Locale.ROOT, "%.4f", l.getScore()));
                    sb.append("]</span>");
                }
                sb.append("</li>");
            }
            sb.append("</ol>");
        }
        sb.append("</section>");
        return sb.toString();
    }

    /**
     * Escape angle brackets and ampersands to avoid HTML injection.  This method intentionally does
     * not escape quotes because the output is not used within an attribute context.
     *
     * @param s the input string
     * @return the escaped string
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}