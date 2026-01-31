package com.example.lms.service.trace;

import com.example.lms.trace.attribution.TraceAblationAttributionResult;
import com.example.lms.trace.attribution.TraceAblationAttributionService;
import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search Trace UI panel HTML builder.
 *
 * Requirements:
 * - (A) Show raw snippets (full text returned by search engines).
 * - (B) Show final context (rerank TopK + vector/RAG TopK) in a separate
 * section.
 *
 * Notes:
 * - Raw snippets may contain &lt;a&gt; tags, so do not escape them.
 * - Final context uses Content.textSegment() to extract text and metadata
 * safely.
 */
@Component
public class TraceHtmlBuilder {

    private final TraceAblationAttributionService traceAblationAttributionService;

    public TraceHtmlBuilder(TraceAblationAttributionService traceAblationAttributionService) {
        this.traceAblationAttributionService = traceAblationAttributionService;
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    public String buildSplitPanel(
            NaverSearchService.SearchTrace rawTrace,
            List<String> rawSnippets,
            List<Content> webTopK,
            List<Content> vectorTopK) {
        return buildSplitPanel(rawTrace, rawSnippets, webTopK, vectorTopK, null);
    }

    public String buildSplitPanel(
            NaverSearchService.SearchTrace rawTrace,
            List<String> rawSnippets,
            List<Content> webTopK,
            List<Content> vectorTopK,
            Map<String, Object> extraMeta) {
        if (rawTrace == null) {
            return "";
        }

        int rawCount = (rawSnippets == null) ? 0 : rawSnippets.size();
        boolean webEnabled = webTopK != null;
        boolean vectorEnabled = vectorTopK != null;

        RiskLevel risk = evaluateRisk(extraMeta);
        String riskClass = cssRiskClass(risk);
        boolean autoOpen = risk != RiskLevel.OK;

        String summaryLine = buildSummaryLine(rawTrace, rawCount, webTopK, vectorTopK, extraMeta, risk);

        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"search-trace ").append(riskClass).append("\"");
        if (autoOpen) {
            sb.append(" open");
        }
        sb.append(">");
        sb.append("<summary>");
        sb.append(summaryLine);
        sb.append("</summary>");
        sb.append("<div class='trace-body'>");

        sb.append(renderRawSearchPanel(rawTrace, rawSnippets, extraMeta));
        sb.append(renderTopKPanel("B) Final Context (LLM Input)",
                webEnabled ? webTopK : null,
                vectorEnabled ? vectorTopK : null));
        sb.append(renderOrchestrationPanel(extraMeta, webTopK, vectorTopK, risk));

        sb.append("</div>");
        sb.append("</details>");
        return sb.toString();
    }

    /**
     * Build a self-contained HTML page for a
     * {@link com.example.lms.trace.TraceSnapshotStore} snapshot.
     *
     * <p>
     * This is intentionally lightweight: it renders core request/trace identifiers,
     * then reuses the orchestration diagnostics panel grouping (ML/Orch/Embedding,
     * etc.)
     * </p>
     */
    public String buildSnapshotHtml(
            String snapshotId,
            String tsIso,
            String sid,
            String traceId,
            String requestId,
            String reason,
            String method,
            String path,
            Integer status,
            String error,
            Map<String, Object> trace,
            Map<String, String> mdc) {
        Map<String, Object> extraMeta = new java.util.LinkedHashMap<>();
        if (trace != null) {
            extraMeta.putAll(trace);
        }
        // Add a few MDC values for convenience (do not dump full MDC here).
        if (mdc != null) {
            putIfNotBlank(extraMeta, "mdc.sid", mdc.get("sid"));
            putIfNotBlank(extraMeta, "mdc.sessionId", mdc.get("sessionId"));
            putIfNotBlank(extraMeta, "mdc.trace", mdc.get("trace"));
            putIfNotBlank(extraMeta, "mdc.traceId", mdc.get("traceId"));
            putIfNotBlank(extraMeta, "mdc.x-request-id", mdc.get("x-request-id"));
            putIfNotBlank(extraMeta, "mdc.dbgSearch", mdc.get("dbgSearch"));
        }

        RiskLevel risk = evaluateRisk(extraMeta);
        String riskClass = cssRiskClass(risk);

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>");
        sb.append("<title>Trace Snapshot ").append(escape(snapshotId)).append("</title>");
        // Minimal inline CSS so the page is readable standalone.
        sb.append("<style>")
                .append("body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:16px}")
                .append(".text-muted{color:#666}")
                .append(".small{font-size:12px}")
                .append(".trace-mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}")
                .append(".trace-section{border:1px solid #eee;border-radius:8px;padding:10px 12px;margin:12px 0}")
                .append(".trace-panel-header{margin-bottom:8px}")
                .append(".trace-kv code{background:#f7f7f7;padding:2px 4px;border-radius:4px}")
                .append(".search-trace{display:block}")
                .append(".trace-risk-ok{border-left:4px solid #2e7d32}")
                .append(".trace-risk-warn{border-left:4px solid #ef6c00}")
                .append(".trace-risk-high{border-left:4px solid #c62828}")
                .append(".trace-risk-ok summary{color:#111}")
                .append(".trace-risk-warn summary{color:#9a6a00}")
                .append(".trace-risk-high summary{color:#9a0000}")
                .append("</style>");
        sb.append("</head><body>");

        sb.append("<details class=\"search-trace ").append(riskClass).append("\" open>");
        sb.append("<summary>");
        sb.append("<span class=\"trace-mono\">").append(escape(safeValue(reason))).append("</span>");
        if (status != null) {
            sb.append(" <span class=\"text-muted small\">status=").append(escape(String.valueOf(status)))
                    .append("</span>");
        }
        if (path != null && !path.isBlank()) {
            sb.append(" <span class=\"text-muted small\">\u00b7 ").append(escape(path)).append("</span>");
        }
        sb.append("</summary>");

        sb.append("<div class='trace-body'>");

        sb.append("<div class='trace-section'>");
        sb.append(renderPanelHeader("Snapshot Meta", "Identifiers & capture context"));
        sb.append("<div class='trace-kv'>");
        sb.append(kvLine("id", snapshotId));
        sb.append(kvLine("ts", tsIso));
        sb.append(kvLine("sid", sid));
        sb.append(kvLine("traceId", traceId));
        sb.append(kvLine("requestId", requestId));
        sb.append(kvLine("reason", reason));
        sb.append(kvLine("method", method));
        sb.append(kvLine("path", path));
        sb.append(kvLine("status", status == null ? null : String.valueOf(status)));
        if (error != null && !error.isBlank()) {
            sb.append(kvLine("error", error));
        }
        sb.append("</div>");
        sb.append("</div>");

        // Reuse the existing orchestration panel to show grouped breadcrumbs.
        sb.append(renderOrchestrationPanel(extraMeta, null, null, risk));

        // Raw dump for completeness.
        sb.append("<details class='trace-fold'>");
        sb.append("<summary><strong>Raw TraceStore</strong> <span class='text-muted small'>keys=")
                .append(extraMeta == null ? 0 : extraMeta.size()).append("</span></summary>");
        sb.append("<div class='trace-kv'>");
        if (extraMeta != null) {
            java.util.List<String> keys = new java.util.ArrayList<>(extraMeta.keySet());
            java.util.Collections.sort(keys);
            for (String k : keys) {
                Object v = extraMeta.get(k);
                sb.append("<div><code>").append(escape(k)).append("</code>: <span class='trace-mono'>")
                        .append(escape(safeValue(v))).append("</span></div>");
            }
        }
        sb.append("</div>");
        sb.append("</details>");

        sb.append("</div>");
        sb.append("</details>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String renderRawSearchPanel(NaverSearchService.SearchTrace rawTrace, List<String> rawSnippets,
            Map<String, Object> extraMeta) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append(renderPanelHeader("A) Raw Snippets",
                "Search engine raw results (" + (rawSnippets == null ? 0 : rawSnippets.size()) + " items)"));

        // Web trace detail/steps are shown only when dbgSearch is enabled (request flag
        // or boost).
        boolean dbgEnabled = extraMeta != null
                && (truthy(extraMeta.get("dbg.search.enabled")) || truthy(extraMeta.get("dbg.search.boost.active")));
        boolean boostActive = extraMeta != null && truthy(extraMeta.get("dbg.search.boost.active"));

        if (dbgEnabled && rawTrace != null) {
            sb.append("<details class='trace-fold' open>");
            sb.append("<summary><strong>Web Trace</strong>");
            sb.append(" <span class='text-muted small'>provider=").append(escape(safeValue(rawTrace.provider)))
                    .append(" · total=").append(escape(fmtMs(rawTrace.totalMs))).append("</span>");
            sb.append("</summary>");

            sb.append("<div class='trace-kv'>");
            sb.append("<div><code>query</code>: <span class='trace-mono'>").append(escape(safeValue(rawTrace.query)))
                    .append("</span></div>");
            sb.append("<div><code>domainFilterEnabled</code>: <b>").append(rawTrace.domainFilterEnabled).append("</b>");
            if (!rawTrace.domainFilterEnabled && rawTrace.reasonDomainFilterDisabled != null
                    && !rawTrace.reasonDomainFilterDisabled.isBlank()) {
                sb.append(" <span class='text-muted small'>(").append(escape(rawTrace.reasonDomainFilterDisabled))
                        .append(")</span>");
            }
            sb.append("</div>");
            sb.append("<div><code>keywordFilterEnabled</code>: <b>").append(rawTrace.keywordFilterEnabled)
                    .append("</b>");
            if (!rawTrace.keywordFilterEnabled && rawTrace.reasonKeywordFilterDisabled != null
                    && !rawTrace.reasonKeywordFilterDisabled.isBlank()) {
                sb.append(" <span class='text-muted small'>(").append(escape(rawTrace.reasonKeywordFilterDisabled))
                        .append(")</span>");
            }
            sb.append("</div>");
            if (rawTrace.suffixApplied != null && !rawTrace.suffixApplied.isBlank()) {
                sb.append("<div><code>suffixApplied</code>: <span class='trace-mono'>")
                        .append(escape(rawTrace.suffixApplied)).append("</span></div>");
            }
            if (rawTrace.orgResolved) {
                sb.append("<div><code>orgResolved</code>: <span class='trace-mono'>true</span></div>");
            }
            if (rawTrace.orgCanonical != null && !rawTrace.orgCanonical.isBlank()) {
                sb.append("<div><code>orgCanonical</code>: <span class='trace-mono'>")
                        .append(escape(rawTrace.orgCanonical)).append("</span></div>");
            }
            if (boostActive && rawTrace.siteFiltersApplied != null && !rawTrace.siteFiltersApplied.isEmpty()) {
                sb.append("<div><code>siteFiltersApplied</code>: <span class='trace-mono'>");
                int lim = Math.min(rawTrace.siteFiltersApplied.size(), 8);
                for (int i = 0; i < lim; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(escape(String.valueOf(rawTrace.siteFiltersApplied.get(i))));
                }
                if (rawTrace.siteFiltersApplied.size() > lim)
                    sb.append(" …");
                sb.append("</span></div>");
            }
            sb.append("</div>");

            if (rawTrace.steps != null && !rawTrace.steps.isEmpty()) {
                String detailProviderCsv = safeValueOrDefault(
                        extraMeta.get("dbg.search.trace.steps.boost.detailProviderContains"), "");
                boolean boostDetail = boostActive && matchesAnyCsvSubstring(rawTrace.provider, detailProviderCsv);

                int maxRowsDefault = toInt(extraMeta.get("dbg.search.trace.steps.maxRows"));
                int maxRowsBoost = toInt(extraMeta.get("dbg.search.trace.steps.maxRows.boost"));
                int maxRowsBoostDetail = toInt(extraMeta.get("dbg.search.trace.steps.maxRows.boost.detail"));
                if (maxRowsDefault <= 0)
                    maxRowsDefault = 20;
                if (maxRowsBoost <= 0)
                    maxRowsBoost = 40;
                if (maxRowsBoostDetail <= 0)
                    maxRowsBoostDetail = 80;

                int maxRows = boostActive ? (boostDetail ? maxRowsBoostDetail : maxRowsBoost) : maxRowsDefault;
                int maxQuery = boostActive ? (boostDetail ? 220 : 160) : 80;
                boolean showQlen = boostDetail;
                int cols = showQlen ? 6 : 5;

                sb.append("<details class='trace-fold trace-steps-panel'>");
                sb.append("<summary><span class='trace-mono'>trace steps</span> <span class='text-muted small'>(")
                        .append(rawTrace.steps.size()).append("; showing up to ").append(maxRows).append(")</span>");
                if (boostDetail) {
                    sb.append(" <span class='text-muted small'>(boost detail)</span>");
                }
                sb.append("</summary>");

                // Minimal filter controls (all client-side)
                sb.append("<div class='trace-controls small trace-steps-controls'>");
                sb.append("<label><input type='checkbox' data-steps-filter='nonok'> non-ok</label> ");
                sb.append("<label><input type='checkbox' data-steps-filter='slow'> slow(≥1000ms)</label> ");
                sb.append(
                        "<input type='text' class='trace-input small' placeholder='filter query…' data-steps-filter='q'>");
                sb.append("</div>");

                sb.append("<table class='trace-table small trace-steps-table'>");
                sb.append("<thead><tr>");
                sb.append("<th data-skey='idx' data-stype='num'>#</th>");
                sb.append("<th data-skey='query' data-stype='txt'>query</th>");
                if (showQlen)
                    sb.append("<th data-skey='qlen' data-stype='num'>qLen</th>");
                sb.append("<th data-skey='returned' data-stype='num'>returned</th>");
                sb.append("<th data-skey='kept' data-stype='num'>kept</th>");
                sb.append("<th data-skey='took' data-stype='num'>took</th>");
                sb.append("</tr></thead><tbody>");

                int idx2 = 0;
                for (var st : rawTrace.steps) {
                    idx2++;
                    if (idx2 > maxRows) {
                        sb.append("<tr><td colspan='").append(cols)
                                .append("' class='text-muted'>…(truncated)…</td></tr>");
                        break;
                    }
                    if (st == null)
                        continue;
                    String qfull = safeValueOrDefault(st.query, "");
                    int qlen = qfull.length();

                    String qtxt = qfull;
                    if (qtxt.length() > maxQuery)
                        qtxt = qtxt.substring(0, maxQuery) + "…";
                    String qData = qfull;
                    if (qData.length() > 400)
                        qData = qData.substring(0, 400) + "…";

                    sb.append("<tr")
                            .append(" data-idx='").append(idx2).append("'")
                            .append(" data-query='").append(escapeAttr(qData)).append("'")
                            .append(" data-qlen='").append(qlen).append("'")
                            .append(" data-returned='").append(st.returned).append("'")
                            .append(" data-kept='").append(st.afterFilter).append("'")
                            .append(" data-took='").append(st.tookMs).append("'")
                            .append(">");

                    sb.append("<td>").append(idx2).append("</td>");
                    sb.append("<td class='trace-mono'>").append(escape(qtxt)).append("</td>");
                    if (showQlen)
                        sb.append("<td>").append(qlen).append("</td>");
                    sb.append("<td>").append(st.returned).append("</td>");
                    sb.append("<td>").append(st.afterFilter).append("</td>");
                    sb.append("<td>").append(escape(fmtMs(st.tookMs))).append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</tbody></table>");

                // Column sorting + filter wiring (safe no-op if browser blocks scripts)
                sb.append(
                        """
                                <script data-trace-script="1">
                                (function(){
                                  function wire(panel){
                                    if(!panel || panel.__stepsWired) return;
                                    panel.__stepsWired = true;
                                    var table = panel.querySelector('table.trace-steps-table');
                                    if(!table) return;
                                    var tbody = table.querySelector('tbody');
                                    if(!tbody) return;

                                    var qInput = panel.querySelector('[data-steps-filter="q"]');
                                    var cbNonOk = panel.querySelector('[data-steps-filter="nonok"]');
                                    var cbSlow = panel.querySelector('[data-steps-filter="slow"]');

                                    function rowAttr(row, key){
                                      try { return row.getAttribute('data-' + key) || ''; } catch(e){ return ''; }
                                    }
                                    function sortRows(key, dir){
                                      var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
                                      rows.sort(function(a,b){
                                        var av = rowAttr(a, key);
                                        var bv = rowAttr(b, key);
                                        var numeric = (key === 'idx' || key === 'returned' || key === 'kept' || key === 'took' || key === 'qlen');
                                        if(numeric){
                                          var an = parseInt(av || '0', 10) || 0;
                                          var bn = parseInt(bv || '0', 10) || 0;
                                          return dir === 'asc' ? (an - bn) : (bn - an);
                                        }
                                        av = (av || '').toLowerCase();
                                        bv = (bv || '').toLowerCase();
                                        if(av < bv) return dir === 'asc' ? -1 : 1;
                                        if(av > bv) return dir === 'asc' ? 1 : -1;
                                        return 0;
                                      });
                                      rows.forEach(function(r){ tbody.appendChild(r); });
                                    }
                                    function applyFilter(){
                                      var q = (qInput && qInput.value ? qInput.value : '').toLowerCase();
                                      var nonok = !!(cbNonOk && cbNonOk.checked);
                                      var slow = !!(cbSlow && cbSlow.checked);
                                      var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
                                      rows.forEach(function(r){
                                        var ok = true;
                                        if(q){
                                          var rq = (rowAttr(r, 'query') || '').toLowerCase();
                                          ok = rq.indexOf(q) >= 0;
                                        }
                                        if(nonok){
                                          var ret = parseInt(rowAttr(r, 'returned') || '0', 10) || 0;
                                          var kept = parseInt(rowAttr(r, 'kept') || '0', 10) || 0;
                                          ok = ok && (ret === 0 || kept === 0);
                                        }
                                        if(slow){
                                          var took = parseInt(rowAttr(r, 'took') || '0', 10) || 0;
                                          ok = ok && (took >= 1000);
                                        }
                                        r.style.display = ok ? '' : 'none';
                                      });
                                    }

                                    var headers = Array.prototype.slice.call(panel.querySelectorAll('thead th[data-skey]'));
                                    headers.forEach(function(th){
                                      th.style.cursor = 'pointer';
                                      th.title = (th.title || '') + ' (click to sort)';
                                      th.addEventListener('click', function(){
                                        var key = th.getAttribute('data-skey');
                                        var curKey = table.getAttribute('data-sort-key') || 'idx';
                                        var curDir = table.getAttribute('data-sort-dir') || 'asc';
                                        var dir = (curKey === key) ? (curDir === 'asc' ? 'desc' : 'asc') : 'asc';
                                        table.setAttribute('data-sort-key', key);
                                        table.setAttribute('data-sort-dir', dir);
                                        sortRows(key, dir);
                                      });
                                    });

                                    if(qInput) qInput.addEventListener('input', applyFilter);
                                    if(cbNonOk) cbNonOk.addEventListener('change', applyFilter);
                                    if(cbSlow) cbSlow.addEventListener('change', applyFilter);

                                    // defaults
                                    sortRows('idx', 'asc');
                                    applyFilter();
                                  }
                                  var panels = document.querySelectorAll('details.trace-steps-panel');
                                  for(var i=0;i<panels.length;i++) wire(panels[i]);
                                })();
                                </script>
                                """);

                sb.append("</details>");
            }

            sb.append("</details>");
        }
        if (rawSnippets == null || rawSnippets.isEmpty()) {
            sb.append("<div class='text-muted small'>(No results)</div>");
        } else {
            sb.append("<ol class='trace-raw-list'>");
            int idx = 0;
            for (String snippet : rawSnippets) {
                idx++;
                if (idx > 30) {
                    sb.append("<li>...(truncated)...</li>");
                    break;
                }
                sb.append("<li>").append(snippet).append("</li>");
            }
            sb.append("</ol>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String renderTopKPanel(String title, List<Content> webTopK, List<Content> vectorTopK) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append(renderPanelHeader(title, "Final output after Rerank/Filtering"));

        if (webTopK != null && !webTopK.isEmpty()) {
            sb.append("<div class='trace-sub-title'>Web TopK (" + webTopK.size() + ")</div>");
            sb.append(renderContentList(webTopK, "W", 10));
        } else if (webTopK == null) {
            sb.append("<div class='text-muted small'>Web: disabled</div>");
        } else {
            sb.append("<div class='text-muted small'>Web: 0 items</div>");
        }

        if (vectorTopK != null && !vectorTopK.isEmpty()) {
            sb.append("<div class='trace-sub-title'>Vector TopK (" + vectorTopK.size() + ")</div>");
            sb.append(renderContentList(vectorTopK, "V", 10));
        } else if (vectorTopK == null) {
            sb.append("<div class='text-muted small'>Vector: disabled</div>");
        } else {
            sb.append("<div class='text-muted small'>Vector: 0 items</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private static String renderPanelHeader(String title, String desc) {
        return "<div class='trace-panel-header'><strong>" + escape(title) + "</strong> " +
                "<span class='text-muted small'>" + escape(desc) + "</span></div>";
    }

    private static void putIfNotBlank(Map<String, Object> m, String k, String v) {
        if (m == null || k == null || k.isBlank())
            return;
        if (v == null || v.isBlank())
            return;
        m.put(k, v);
    }

    private static String kvLine(String k, String v) {
        if (k == null || k.isBlank())
            return "";
        if (v == null || v.isBlank())
            return "";
        return "<div><code>" + escape(k) + "</code>: <span class='trace-mono'>" + escape(v)
                + "</span></div>";
    }

    private static String fmtMs(long ms) {
        if (ms < 1000)
            return ms + "ms";
        else
            return String.format("%.2fs", ms / 1000.0);
    }

    private static String renderContentList(List<Content> list, String tagPrefix, int max) {
        if (list == null || list.isEmpty())
            return "<div class=\"text-muted small\">(empty)</div>";
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<ol class=\"trace-docs\">");
        int idx = 1;
        for (Content c : list) {
            if (c == null)
                continue;
            if (idx > Math.max(1, max))
                break;

            String url = extractUrl(c);
            String title = extractTitle(c);
            String snippet = extractSnippet(c);
            String host = hostOf(url);

            sb.append("<li>");
            sb.append("<div class=\"trace-doc-line\">")
                    .append("<span class=\"trace-tag\">")
                    .append(escape(tagPrefix)).append(idx)
                    .append("</span>");

            if (isHttpUrl(url)) {
                sb.append("<a href=\"").append(escapeAttr(url))
                        .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
                        .append(escape(title))
                        .append("</a>");
                if (host != null && !host.isBlank()) {
                    sb.append("<span class=\"trace-host text-muted\">")
                            .append(escape(host))
                            .append("</span>");
                }
            } else {
                sb.append("<span class=\"trace-title\">").append(escape(title)).append("</span>");
                if (url != null && !url.isBlank()) {
                    sb.append("<span class=\"trace-host text-muted\">").append(escape(url)).append("</span>");
                }
            }
            sb.append("</div>");

            if (snippet != null && !snippet.isBlank()) {
                sb.append("<div class=\"trace-doc-snippet\">").append(escape(snippet)).append("</div>");
            }
            sb.append("</li>");
            idx++;
        }
        sb.append("</ol>");
        return sb.toString();
    }

    private static String extractTitle(Content c) {
        if (c == null)
            return "(No title)";
        try {
            var seg = c.textSegment();
            if (seg != null) {
                try {
                    var md = seg.metadata();
                    if (md != null) {
                        String t = md.getString("title");
                        if (t != null && !t.isBlank())
                            return truncate(t.strip(), 80);
                    }
                } catch (Exception ignore) {
                }
                String text = seg.text();
                if (text != null && !text.isBlank()) {
                    String line1 = text.strip().split("\\r?\\n", 2)[0].strip();
                    if (line1.startsWith("[") && line1.contains("]")) {
                        String inside = line1.substring(1, line1.indexOf(']'));
                        String[] parts = inside.split("\\s*\\|\\s*");
                        if (parts.length > 0 && !parts[0].isBlank())
                            return truncate(parts[0].strip(), 80);
                    }
                    if (!line1.isBlank())
                        return truncate(line1, 80);
                }
            }
        } catch (Exception ignore) {
        }
        return "(No title)";
    }

    private static String extractSnippet(Content c) {
        if (c == null)
            return "";
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text().strip();
                if (t.isBlank())
                    return "";
                String[] lines = t.split("\\r?\\n", 2);
                if (lines.length == 2) {
                    String first = lines[0].strip();
                    if (first.startsWith("[") && first.contains("]"))
                        t = lines[1].strip();
                }
                return truncate(t, 200);
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    private static String extractUrl(Content c) {
        if (c == null)
            return null;
        try {
            var seg = c.textSegment();
            if (seg == null)
                return null;
            try {
                var md = seg.metadata();
                if (md != null) {
                    String url = md.getString("url");
                    if (url == null || url.isBlank())
                        url = md.getString("source");
                    if (url != null && !url.isBlank())
                        return url.strip();
                }
            } catch (Exception ignore) {
            }
            String text = seg.text();
            if (text != null && !text.isBlank()) {
                Matcher m = URL_IN_TEXT.matcher(text);
                if (m.find())
                    return m.group(1);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String hostOf(String url) {
        if (!isHttpUrl(url))
            return null;
        try {
            String host = URI.create(url).getHost();
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHttpUrl(String url) {
        if (url == null)
            return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return "";
        return s.length() > max ? (s.substring(0, max) + "...") : s;
    }

    private enum RiskLevel {
        OK, WARN, HIGH
    }

    private static RiskLevel evaluateRisk(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty())
            return RiskLevel.OK;
        RiskLevel risk = RiskLevel.OK;
        String auxQt = getString(extraMeta, "aux.queryTransformer");
        String auxDis = getString(extraMeta, "aux.disambiguation");
        if (truthy(extraMeta.get("aux.llm.down")) || isNonBlank(auxQt) || isNonBlank(auxDis))
            risk = maxRisk(risk, RiskLevel.WARN);
        if (containsIgnoreCase(auxQt, "breaker") || containsIgnoreCase(auxDis, "breaker")
                || truthy(extraMeta.get("nightmare.breaker.open")))
            risk = maxRisk(risk, RiskLevel.HIGH);
        if (truthy(extraMeta.get("guard.inconsistentTemplate")))
            risk = maxRisk(risk, RiskLevel.WARN);
        String guardAction = getString(extraMeta, "guard.final.action");
        if (guardAction != null) {
            if ("BLOCK".equalsIgnoreCase(guardAction))
                risk = maxRisk(risk, RiskLevel.HIGH);
            else if ("REWRITE".equalsIgnoreCase(guardAction))
                risk = maxRisk(risk, RiskLevel.WARN);
        }
        if (truthy(extraMeta.get("web.rateLimited")) || truthy(extraMeta.get("web.naver.429"))
                || truthy(extraMeta.get("web.brave.429")) || truthy(extraMeta.get("web.naver.skippedByBreaker")))
            risk = maxRisk(risk, RiskLevel.WARN);
        Integer required = toInt(extraMeta.get("guard.minCitations.required"));
        Integer actual = toInt(extraMeta.get("guard.minCitations.actual"));
        if (required != null && actual != null && actual < required)
            risk = maxRisk(risk, RiskLevel.HIGH);

        // Context propagation / correlation leakage signals.
        // These are high-value indicators because they directly affect debugging
        // and can hide the true root cause behind fail-soft placeholders.
        boolean ctxMissing = truthy(extraMeta.get("ctx.propagation.missing"))
                || truthy(extraMeta.get("ctx.correlation.missing"))
                || truthy(extraMeta.get("ctx.mdc.bridge"));
        if (ctxMissing) {
            risk = maxRisk(risk, RiskLevel.WARN);
        }

        // Escalate when the system had to generate placeholder correlation ids.
        boolean ctxGenerated = truthy(extraMeta.get("ctx.propagation.generated"))
                || truthy(extraMeta.get("ctx.correlation.generated"));
        if (ctxGenerated) {
            risk = maxRisk(risk, RiskLevel.HIGH);
        }

        Integer ctxMissingCount = toInt(extraMeta.get("ctx.propagation.missing.count"));
        if (ctxMissingCount == null) {
            ctxMissingCount = toInt(extraMeta.get("ctx.correlation.missing.count"));
        }
        if (ctxMissingCount != null && ctxMissingCount >= 3) {
            risk = maxRisk(risk, RiskLevel.HIGH);
        }

        // Heuristic fallback: if obvious placeholders leak into the request context,
        // treat as missing correlation even if explicit ctx.* anchors weren't recorded.
        String _sid = getString(extraMeta, "sid");
        String _rid = firstNonBlank(
                getString(extraMeta, "x-request-id"),
                getString(extraMeta, "requestId"),
                getString(extraMeta, "trace"),
                getString(extraMeta, "traceId"),
                getString(extraMeta, "trace.id"));
        boolean hasPlaceholder = (containsIgnoreCase(_rid, "rid-missing-") || containsIgnoreCase(_sid, "sid-missing-"));
        if (hasPlaceholder) {
            risk = maxRisk(risk, RiskLevel.WARN);
        }
        return risk;
    }

    private static RiskLevel maxRisk(RiskLevel a, RiskLevel b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return (a.ordinal() >= b.ordinal()) ? a : b;
    }

    private static String cssRiskClass(RiskLevel risk) {
        if (risk == null)
            return "trace-risk-ok";
        return switch (risk) {
            case OK -> "trace-risk-ok";
            case WARN -> "trace-risk-warn";
            case HIGH -> "trace-risk-high";
        };
    }

    private static String badgeHtml(String text, String css) {
        String c = (css == null || css.isBlank()) ? "" : (" " + css);
        return "<span class='trace-risk-badge" + c + "'>" + escape(text) + "</span>";
    }

    private static String riskBadgeHtml(RiskLevel risk) {
        if (risk == null)
            return badgeHtml("OK", "trace-risk-badge-ok");
        return switch (risk) {
            case OK -> badgeHtml("OK", "trace-risk-badge-ok");
            case WARN -> badgeHtml("WARN", "trace-risk-badge-warn");
            case HIGH -> badgeHtml("HIGH", "trace-risk-badge-high");
        };
    }

    private String buildSummaryLine(NaverSearchService.SearchTrace rawTrace, int rawCount, List<Content> webTopK,
            List<Content> vectorTopK, Map<String, Object> extraMeta, RiskLevel risk) {
        boolean webEnabled = webTopK != null;
        boolean vecEnabled = vectorTopK != null;
        int webSz = webEnabled ? webTopK.size() : 0;
        int vecSz = vecEnabled ? vectorTopK.size() : 0;
        String query = (rawTrace.query() == null) ? "" : rawTrace.query().replace("\n", " ").trim();
        if (query.isBlank()) {
            // Fallback: some pipelines store "effective query" in extraMeta
            query = getString(extraMeta, "web.effectiveQuery").replace("\n", " ").trim();
        }
        if (query.isBlank())
            query = "<unset>";

        String provider = (rawTrace.provider() == null) ? "" : rawTrace.provider().replace("\n", " ").trim();
        if (provider.isBlank())
            provider = "<unset>";
        StringBuilder sb = new StringBuilder();
        sb.append("Search Trace - query: ").append(escape(query))
                .append(" - provider: ").append(escape(provider))
                .append(" - raw ").append(rawCount)
                .append(" - final context (web ").append(webEnabled ? webSz : "disabled")
                .append(", vector ").append(vecEnabled ? vecSz : "disabled")
                .append(") - ").append(fmtMs(rawTrace.elapsedMs()))
                .append(" ").append(riskBadgeHtml(risk))
                .append(renderPills(extraMeta));
        return sb.toString();
    }

    private String renderOrchestrationPanel(Map<String, Object> extraMeta, List<Content> webTopK,
            List<Content> vectorTopK, RiskLevel risk) {
        if (extraMeta == null || extraMeta.isEmpty())
            return "";
        String summary = buildOrchestrationSummary(extraMeta, webTopK, vectorTopK);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append(renderPanelHeader("C) Orchestration State (aux / guard)", "Problem tracking and diagnostics"));
        boolean forceOpen = (risk != null && risk != RiskLevel.OK)
                || truthy(extraMeta.get("orch.strike"))
                || truthy(extraMeta.get("orch.bypass"))
                || truthy(extraMeta.get("orch.compression"))
                || truthy(extraMeta.get("orch.webRateLimited"))
                || truthy(extraMeta.get("orch.auxLlmDown"));
        sb.append("<details class='trace-orch'").append(forceOpen ? " open" : "")
                .append(">");
        sb.append("<summary>").append(escape(summary)).append("</summary>");
        sb.append(renderOrchestrationModeCallout(extraMeta));
        sb.append(renderWebFailSoftRiskCallout(extraMeta));
        sb.append(renderSoakWebKpiCopyCallout(extraMeta));
        sb.append(renderTraceAblationAttributionCallout(extraMeta, webTopK, vectorTopK));
        sb.append("<table class='trace-kv'>");
        java.util.Set<String> shown = new java.util.HashSet<>();

        // Make "why STRIKE/BYPASS" visible without digging into scattered fields.
        appendKvGroup(sb, extraMeta, shown, "Mode",
                java.util.List.of("orch.mode", "orch.strike", "orch.compression", "orch.bypass", "orch.reason",
                        "orch.webRateLimited", "orch.auxLlmDown", "orch.highRisk", "orch.irregularity",
                        "orch.userFrustration",
                        "orch.noiseEscape.bypassSilentFailure",
                        "orch.noiseEscape.bypassSilentFailure.escapeP",
                        "orch.noiseEscape.bypassSilentFailure.roll",
                        "bypassReason"));

        // Context propagation / correlation anchors (high-signal debugging surface).
        // Render ctx.propagation.missing.events in a structured way (timeline/table),
        // not raw toString().
        if (extraMeta.containsKey("ctx.propagation.missing.events")) {
            shown.add("ctx.propagation.missing.events"); // handled by appendCtxMissingEvents()
        }
        appendKvPrefixGroup(sb, extraMeta, shown, "Context", "ctx.", 24);
        appendCtxMissingEvents(sb, extraMeta, shown);

        // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_SHOW
        appendOrchPartsTable(sb, extraMeta, shown);

        appendKvGroup(sb, extraMeta, shown, "UAW Thumbnail Recall",
                java.util.List.of("uaw.thumb.recall.enabled", "uaw.thumb.recall.hits", "uaw.thumb.recall.entities"));
        appendKvGroup(sb, extraMeta, shown, "Aux",
                java.util.List.of(
                        "aux.llm.down",
                        "aux.llm.degraded",
                        "aux.llm.hardDown",
                        "aux.down.first",
                        "aux.down.last",
                        "aux.down.events",

                        "aux.blocked",
                        "aux.blocked.first",
                        "aux.blocked.last",
                        "aux.blocked.events",
                        "aux.keywordSelection.blocked",
                        "aux.keywordSelection.blocked.reason",
                        "aux.keywordSelection.degraded",
                        "aux.keywordSelection.degraded.reason",
                        "aux.keywordSelection.degraded.count",
                        "keywordSelection.fallback.seedSource",
                        "keywordSelection.fallback.seed",
                        "keywordSelection.fallback.seed.baseScore",
                        "keywordSelection.fallback.seed.uqScore",
                        "aux.queryTransformer",
                        "aux.queryTransformer.blocked",
                        "aux.queryTransformer.blocked.reason",
                        "aux.queryTransformer.degraded",
                        "aux.queryTransformer.degraded.reason",
                        "aux.queryTransformer.degraded.trigger",
                        "aux.queryTransformer.degraded.count",
                        "qtx.softCooldown.active",
                        "qtx.softCooldown.remainingMs",
                        "aux.disambiguation",
                        "aux.disambiguation.blocked",
                        "aux.disambiguation.blocked.reason",

                        "nightmare.blank.lastKey",
                        "nightmare.blank.last",
                        "nightmare.blank.events",
                        "nightmare.silent.lastKey",
                        "nightmare.silent.last",
                        "nightmare.silent.events",

                        "nightmare.breaker.openAtMs",
                        "nightmare.breaker.openUntilMs",
                        "nightmare.breaker.openUntilMs.last",
                        "nightmare.mode"));
        appendKvGroup(sb, extraMeta, shown, "Guard",
                java.util.List.of("guard.final.action", "guard.final.coverageScore", "guard.inconsistentTemplate",
                        "guard.escalated",
                        "guard.escalation.model",
                        "guard.escalation.quality",
                        "guard.escalation.coverage",
                        "guard.escalation.evidenceCount",
                        "guard.escalation.reason",
                        "guard.escalation.triggers",
                        "guard.escalation.uniqueDomains",
                        "guard.escalation.lowEvidenceDiversity",
                        "guard.escalation.urlBackedCount",
                        "guard.escalation.weakDraft",
                        "guard.escalation.strongEvidenceIgnored",
                        "guard.escalation.inconsistentTemplate",
                        "guard.minCitations.required", "guard.minCitations.actual"));
        appendMinCitationsExplainRow(sb, extraMeta, shown);

        appendKvPrefixGroup(sb, extraMeta, shown, "Plan", "plan.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "QueryPlanner", "queryPlanner.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "NoiseGate", "orch.noiseGate.", 24);

        // Structured prompt events (table) for "click-to-trace" UX.
        if (extraMeta.containsKey("prompt.events")) {
            shown.add("prompt.events"); // handled by appendPromptEvents()
        }
        appendPromptEvents(sb, extraMeta, shown);

        appendKvPrefixGroup(sb, extraMeta, shown, "Prompt", "prompt.", 24);

        // LLM endpoint/model routing + model-guard (OpenAI chat vs responses mismatch)
        // breadcrumbs
        appendKvPrefixGroup(sb, extraMeta, shown, "LLM", "llm.", 24);

        // Structured model routing events (table) for "click-to-trace" UX.
        if (extraMeta.containsKey("ml.router.events")) {
            shown.add("ml.router.events"); // handled by appendMlRouterEvents()
        }
        appendMlRouterEvents(sb, extraMeta, shown);

        // Merge-boundary / stage-handoff breadcrumbs (safe, compact)
        appendKvPrefixGroup(sb, extraMeta, shown, "ML", "ml.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "Embedding", "embed.", 24);

        // Custom (human-friendly) debug UX for web.* fields
        appendWebSelectedTerms(sb, extraMeta, shown);
        appendWebNaverPlanHintBoostOnlyOverlay(sb, extraMeta, shown);
        appendWebAwaitEvents(sb, extraMeta, shown);
        appendWebFailSoftRuns(sb, extraMeta, shown);

        appendKvPrefixGroup(sb, extraMeta, shown, "Web", "web.", 40);
        sb.append("</table></details></div>");
        return sb.toString();
    }

    private static String buildOrchestrationSummary(Map<String, Object> extraMeta, List<Content> webTopK,
            List<Content> vectorTopK) {
        java.util.List<String> parts = new java.util.ArrayList<>();

        // Bubble up STRIKE/BYPASS/... to the collapsed summary so it's visible without
        // scrolling.
        String orchMode = firstNonBlank(getString(extraMeta, "orch.mode"), getString(extraMeta, "orch.modeLabel"));
        if (isNonBlank(orchMode) && !"NORMAL".equalsIgnoreCase(orchMode)) {
            parts.add("mode " + orchMode);
        }
        String planId = getString(extraMeta, "plan.id");
        if (isNonBlank(planId))
            parts.add("plan " + planId);
        String order = firstNonBlank(getString(extraMeta, "retrieval.order.override"),
                getString(extraMeta, "plan.retrievalOrder"));
        if (isNonBlank(order))
            parts.add("order " + order);
        parts.add("web " + (webTopK != null ? webTopK.size() : "disabled"));
        parts.add("vector " + (vectorTopK != null ? vectorTopK.size() : "disabled"));
        return parts.isEmpty() ? "diagnostics" : String.join(" / ", parts);
    }

    /**
     * Compact, single-glance explanation to answer: "왜 BYPASS/STRIKE가 켜졌는지".
     *
     * Rendered above the orchestration KV table so it is visible on one screen.
     */
    private static String renderOrchestrationModeCallout(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty())
            return "";

        String mode = firstNonBlank(getString(extraMeta, "orch.mode"), getString(extraMeta, "orch.modeLabel"));
        if (!isNonBlank(mode))
            mode = "NORMAL";

        boolean strike = truthy(extraMeta.get("orch.strike"));
        boolean bypass = truthy(extraMeta.get("orch.bypass"));
        boolean compression = truthy(extraMeta.get("orch.compression"));
        boolean auxDown = truthy(extraMeta.get("orch.auxLlmDown"));
        boolean webRateLimited = truthy(extraMeta.get("orch.webRateLimited"));

        String reason = firstNonBlank(getString(extraMeta, "orch.reason"), getString(extraMeta, "bypassReason"));
        if (!isNonBlank(reason)) {
            Object reasonsObj = extraMeta.get("orch.reasons");
            if (reasonsObj instanceof java.util.Collection<?> col) {
                java.util.List<String> parts = new java.util.ArrayList<>();
                for (Object o : col) {
                    if (o == null)
                        continue;
                    String s = String.valueOf(o).trim();
                    if (!s.isBlank())
                        parts.add(s);
                }
                if (!parts.isEmpty())
                    reason = String.join(", ", parts);
            }
        }

        String accent = "#4a90e2";
        if (strike)
            accent = "#d33";
        else if (bypass)
            accent = "#f90";
        else if (compression)
            accent = "#c90";

        StringBuilder sb = new StringBuilder();
        sb.append(
                "<div class='trace-orch-callout' style='margin:10px 0;padding:10px;border:1px solid #ddd;border-left:6px solid ")
                .append(accent)
                .append(";background:#fafafa;'>");
        sb.append("<div><b>Mode</b>: ").append(escape(mode)).append("</div>");
        sb.append("<div style='margin-top:4px;font-size:12px;opacity:0.9;'>")
                .append("STRIKE=").append(strike)
                .append(", COMPRESSION=").append(compression)
                .append(", BYPASS=").append(bypass)
                .append(", webRateLimited=").append(webRateLimited)
                .append(", auxDown=").append(auxDown)
                .append("</div>");

        if (isNonBlank(reason)) {
            sb.append("<div style='margin-top:6px;'><b>Why</b>: ").append(escape(reason)).append("</div>");
        }

        Object thumbHitsObj = extraMeta.get("uaw.thumb.recall.hits");
        Integer thumbHits = null;
        if (thumbHitsObj instanceof Number n) {
            thumbHits = n.intValue();
        } else if (thumbHitsObj != null) {
            try {
                thumbHits = Integer.parseInt(String.valueOf(thumbHitsObj).trim());
            } catch (Exception ignore) {
                // ignore
            }
        }
        if (thumbHits != null && thumbHits > 0) {
            String entities = getString(extraMeta, "uaw.thumb.recall.entities");
            sb.append("<div style='margin-top:6px;'><b>UAW Thumbnail</b>: recall hits=")
                    .append(thumbHits);
            if (isNonBlank(entities)) {
                sb.append(" (").append(escape(entities)).append(")");
            }
            sb.append("</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private static String renderWebFailSoftRiskCallout(Map<String, Object> extraMeta) {
        try {
            if (extraMeta == null || extraMeta.isEmpty()) {
                return "";
            }

            long deficit = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.deficit"));
            long maxRemainingMs = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.max.remainingMs"));
            long maxDelayMs = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.max.delayMs"));
            long skippedCooldownCount = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.skipped.cooldown.count"));

            boolean attempted = truthy(extraMeta.get("web.failsoft.minCitationsRescue.attempted"));
            boolean satisfied = truthy(extraMeta.get("web.failsoft.minCitationsRescue.satisfied"));

            // If nothing interesting happened, do not add visual noise.
            if (deficit <= 0 && maxRemainingMs <= 0 && maxDelayMs <= 0 && skippedCooldownCount <= 0 && !attempted) {
                return "";
            }

            String tradeoffClass = getString(extraMeta, "web.failsoft.rateLimitBackoff.cooldownTradeoff.class");
            String tradeoffReason = getString(extraMeta, "web.failsoft.rateLimitBackoff.cooldownTradeoff.reason");
            long awaitReconciledApplyTimes = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.awaitTimeoutReconciledApplyTimes"));

            long needed = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.needed"));
            long citeable = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.citeableCount"));
            boolean preflightEligible = truthy(extraMeta.get("web.failsoft.minCitationsRescue.preflight.eligible"));
            String preflightBlockReason = getString(extraMeta, "web.failsoft.minCitationsRescue.preflight.blockReason");
            long candCount = toLong(extraMeta.get("web.failsoft.minCitationsRescue.preflight.candidates.count"));
            String blockReason = getString(extraMeta, "web.failsoft.minCitationsRescue.blockReason");
            long inserted = toLong(extraMeta.get("web.failsoft.minCitationsRescue.insertedCount"));

            StringBuilder sb = new StringBuilder();
            sb.append("<div class='callout trace-callout'>");
            sb.append("<div class='callout-title'>Web FailSoft Risk</div>");
            sb.append("<div class='callout-body'>");

            // Backoff summary
            sb.append("<div><b>Backoff</b>: ");
            sb.append(isNonBlank(tradeoffClass) ? escape(tradeoffClass) : "n/a");
            if (isNonBlank(tradeoffReason)) {
                sb.append(" <span class='muted'>(").append(escape(tradeoffReason)).append(")</span>");
            }
            sb.append("</div>");

            sb.append("<div class='muted'>");
            sb.append("skipped.cooldown.count=").append(skippedCooldownCount);
            sb.append(", max.delayMs=").append(maxDelayMs);
            sb.append(", max.remainingMs=").append(maxRemainingMs);
            sb.append(", awaitTimeoutReconciledApplyTimes=").append(awaitReconciledApplyTimes);
            sb.append("</div>");

            // Provider details (optional)
            sb.append("<details><summary class='muted'>provider details</summary>");
            sb.append("<ul>");

            // NAVER
            sb.append("<li><b>naver</b>: ");
            String nSkipReason = getString(extraMeta, "web.naver.skipped.reason");
            long nRemaining = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.naver.remainingMs"));
            String nLastKind = getString(extraMeta, "web.failsoft.rateLimitBackoff.naver.last.kind");
            long nLastStreak = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.naver.last.streak"));
            long nLastDelay = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.naver.last.delayMs"));
            boolean nCapHit = truthy(extraMeta.get("web.failsoft.rateLimitBackoff.naver.last.capHit"));
            sb.append("skip=").append(isNonBlank(nSkipReason) ? escape(nSkipReason) : "-");
            sb.append(", remainingMs=").append(nRemaining);
            sb.append(", last=").append(isNonBlank(nLastKind) ? escape(nLastKind) : "-");
            sb.append("/").append(nLastStreak);
            sb.append(", delayMs=").append(nLastDelay);
            sb.append(", capHit=").append(nCapHit);
            sb.append("</li>");

            // BRAVE
            sb.append("<li><b>brave</b>: ");
            String bSkipReason = getString(extraMeta, "web.brave.skipped.reason");
            long bRemaining = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.brave.remainingMs"));
            String bLastKind = getString(extraMeta, "web.failsoft.rateLimitBackoff.brave.last.kind");
            long bLastStreak = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.brave.last.streak"));
            long bLastDelay = toLong(extraMeta.get("web.failsoft.rateLimitBackoff.brave.last.delayMs"));
            boolean bCapHit = truthy(extraMeta.get("web.failsoft.rateLimitBackoff.brave.last.capHit"));
            sb.append("skip=").append(isNonBlank(bSkipReason) ? escape(bSkipReason) : "-");
            sb.append(", remainingMs=").append(bRemaining);
            sb.append(", last=").append(isNonBlank(bLastKind) ? escape(bLastKind) : "-");
            sb.append("/").append(bLastStreak);
            sb.append(", delayMs=").append(bLastDelay);
            sb.append(", capHit=").append(bCapHit);
            sb.append("</li>");

            sb.append("</ul>");
            sb.append("</details>");

            // MinCitations rescue summary
            sb.append("<div style='margin-top:8px'><b>MinCitations rescue</b>: ");
            if (attempted) {
                sb.append(satisfied ? "OK" : "UNSAT");
                if (!satisfied && isNonBlank(blockReason)) {
                    sb.append(" <span class='muted'>(").append(escape(blockReason)).append(")</span>");
                }
            } else if (preflightEligible) {
                sb.append("READY");
            } else if (deficit > 0) {
                sb.append("SKIP");
                if (isNonBlank(preflightBlockReason)) {
                    sb.append(" <span class='muted'>(").append(escape(preflightBlockReason)).append(")</span>");
                }
            } else {
                sb.append("n/a");
            }
            sb.append(" — needed=").append(needed);
            sb.append(", citeable=").append(citeable);
            sb.append(", deficit=").append(deficit);
            sb.append(", inserted=").append(inserted);
            sb.append("</div>");

            sb.append("<div class='muted'>preflight candidates: ").append(candCount).append("</div>");

            Object top3Obj = extraMeta.get("web.failsoft.minCitationsRescue.preflight.candidates.top3");
            if (top3Obj instanceof java.util.List<?> list && !list.isEmpty()) {
                int max = Math.min(3, list.size());
                sb.append("<details><summary class='muted'>candidate queries (top ").append(max).append(")</summary>");
                sb.append("<ol>");
                int i = 0;
                for (Object o : list) {
                    if (o == null) {
                        continue;
                    }
                    String q = String.valueOf(o);
                    if (q.length() > 180) {
                        q = q.substring(0, 180) + "...";
                    }
                    sb.append("<li><code>").append(escape(q)).append("</code></li>");
                    i++;
                    if (i >= max) {
                        break;
                    }
                }
                sb.append("</ol>");
                sb.append("</details>");
            }

            sb.append("</div>");
            sb.append("</div>");
            return sb.toString();
        } catch (Exception ignore) {
            return "";
        }
    }


    /**
     * UX helper for soak/regression runs.
     *
     * When {@code nova.orch.web-failsoft.emit-soak-kpi-json=true} is enabled, the
     * WebFailSoft aspect emits
     * a fixed-schema SOAK_WEB_KPI NDJSON line and also stores it into TraceStore:
     * <ul>
     * <li>{@code web.failsoft.soakKpiJson.last}</li>
     * <li>{@code web.failsoft.soakKpiJson.runId.&lt;runId&gt;}</li>
     * </ul>
     * This callout adds a Copy button so operators can quickly lift 2~3 lines from
     * the UI.
     */
    private static String renderSoakWebKpiCopyCallout(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) {
            return "";
        }
        final String runPrefix = "web.failsoft.soakKpiJson.runId.";
        List<Map.Entry<Long, String>> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : extraMeta.entrySet()) {
            if (e == null || e.getKey() == null) {
                continue;
            }
            if (!e.getKey().startsWith(runPrefix)) {
                continue;
            }
            long rid = -1L;
            try {
                rid = Long.parseLong(e.getKey().substring(runPrefix.length()));
            } catch (Exception ignore) {
                rid = -1L;
            }
            String val = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (isNonBlank(val)) {
                lines.add(new AbstractMap.SimpleEntry<>(rid, val));
            }
        }
        lines.sort(Comparator.comparingLong(Map.Entry::getKey));
        if (lines.size() > 3) {
            lines = lines.subList(lines.size() - 3, lines.size());
        }
        if (lines.isEmpty()) {
            String last = getString(extraMeta, "web.failsoft.soakKpiJson.last");
            if (!isNonBlank(last)) {
                return "";
            }
            lines.add(new AbstractMap.SimpleEntry<>(-1L, last));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(
                "<div class='trace-orch-callout' style='margin:10px 0; padding:10px 12px; border-left:4px solid #2c7; background:#f9fffb; border-radius:6px;'>");
        sb.append("<div style='font-weight:700; margin-bottom:6px;'>SOAK_WEB_KPI (copy)</div>");
        sb.append(
                "<div style='font-size:12px; color:#555; margin-bottom:8px;'>Trace 패널에서 2~3줄 바로 복사 → DC-0~DC-4 매칭용</div>");

        int idx = 0;
        for (Map.Entry<Long, String> ent : lines) {
            idx++;
            String json = ent.getValue() == null ? "" : ent.getValue();
            String id = "soak-web-kpi-" + Math.abs((json + "|" + idx).hashCode());
            sb.append("<div style='display:flex; align-items:center; gap:8px; margin:6px 0 2px 0;'>");
            sb.append("<button type='button' data-copy-target='").append(escapeAttr(id))
                    .append("' style='padding:2px 8px; font-size:12px; cursor:pointer;'>Copy</button>");
            if (ent.getKey() >= 0) {
                sb.append("<span style='font-size:12px; color:#777;'>runId=").append(ent.getKey()).append("</span>");
            }
            sb.append("</div>");
            sb.append("<pre id='").append(escapeAttr(id))
                    .append("' style='white-space:pre-wrap; word-break:break-word; margin:0; padding:8px; border:1px solid #eee; background:#fff; border-radius:6px;'>");
            sb.append(escape(json));
            sb.append("</pre>");
        }

        sb.append("""
                <script data-trace-script="1">
                (function(){
                  function fallbackCopy(text){
                    var ta=document.createElement('textarea');
                    ta.value=text||'';
                    ta.setAttribute('readonly','');
                    ta.style.position='fixed';
                    ta.style.opacity='0';
                    document.body.appendChild(ta);
                    ta.select();
                    try{document.execCommand('copy');}catch(e){}
                    document.body.removeChild(ta);
                  }
                  function copyText(text){
                    if(!text) return;
                    if(navigator.clipboard && navigator.clipboard.writeText){
                      navigator.clipboard.writeText(text).catch(function(){ fallbackCopy(text); });
                    } else {
                      fallbackCopy(text);
                    }
                  }
                  var btns=document.querySelectorAll('button[data-copy-target]');
                  for(var i=0;i<btns.length;i++){
                    var b=btns[i];
                    if(b.__copyWired) continue;
                    b.__copyWired=true;
                    b.addEventListener('click', function(ev){
                      var t=ev.currentTarget.getAttribute('data-copy-target');
                      var el=document.getElementById(t);
                      if(!el) return;
                      var txt=el.textContent || el.innerText || '';
                      copyText(txt);
                      var prev=ev.currentTarget.textContent;
                      ev.currentTarget.textContent='Copied';
                      setTimeout(function(){ ev.currentTarget.textContent=prev; }, 700);
                    });
                  }
                })();
                </script>
                """);
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Trace-Ablation Attribution (TAA) callout.
     *
     * <p>
     * This is a best-effort, debug-only explanation block.
     * It must never fail the main request, so it is wrapped in try/catch.
     */
    private String renderTraceAblationAttributionCallout(
            Map<String, Object> extraMeta,
            List<Content> webTopK,
            List<Content> vectorTopK) {
        try {
            TraceAblationAttributionResult res = traceAblationAttributionService.analyze(extraMeta, webTopK,
                    vectorTopK);
            if (res == null || res.contributors() == null || res.contributors().isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(
                    "<div style=\"margin:8px 0 10px 0; padding:10px 12px; border-left:4px solid #4a90e2; background:#f7fbff; border-radius:6px;\">");
            sb.append("<div style=\"font-weight:700; margin-bottom:4px;\">Trace-Ablation Attribution</div>");
            sb.append("<div style=\"font-size:12px; color:#555; margin-bottom:8px;\">");
            sb.append("outcome=").append(escape(res.outcome()));
            sb.append(" / risk≈").append(String.format(java.util.Locale.ROOT, "%.3f", res.outcomeRisk()));
            sb.append(" / v=").append(escape(res.version()));
            sb.append("</div>");

            sb.append("<div style=\"font-size:13px;\">");
            sb.append("<details open><summary style=\"cursor:pointer;\"><b>Top contributors</b></summary>");
            sb.append("<ol style=\"margin:8px 0 0 18px; padding:0;\">");

            int limit = Math.min(6, res.contributors().size());
            for (int i = 0; i < limit; i++) {
                TraceAblationAttributionResult.Contributor c = res.contributors().get(i);
                int pct = (int) Math.round(c.contribution() * 100.0);
                sb.append("<li style=\"margin:6px 0;\">");
                sb.append("<details>");
                sb.append("<summary style=\"cursor:pointer;\">");
                sb.append("<span style=\"display:inline-block; min-width:42px; font-weight:700;\">" + pct + "%</span>");
                sb.append("<span style=\"color:#666;\">[" + escape(c.group()) + "]</span> ");
                sb.append(escape(c.title()));
                sb.append("</summary>");

                if (c.evidence() != null && !c.evidence().isEmpty()) {
                    sb.append("<div style=\"margin-top:6px;\"><b>evidence</b>");
                    sb.append(renderSimpleList(c.evidence()));
                    sb.append("</div>");
                }
                if (c.recommendations() != null && !c.recommendations().isEmpty()) {
                    sb.append("<div style=\"margin-top:6px;\"><b>fix hints</b>");
                    sb.append(renderSimpleList(c.recommendations()));
                    sb.append("</div>");
                }
                sb.append("</details>");
                sb.append("</li>");
            }
            sb.append("</ol></details>");

            if (res.beams() != null && !res.beams().isEmpty()) {
                sb.append("<details style=\"margin-top:10px;\">");
                sb.append("<summary style=\"cursor:pointer;\"><b>Self-Ask beams</b></summary>");
                sb.append(
                        "<div style=\"margin-top:6px; font-size:12px; color:#666;\">beam search over evidence-weighted hypotheses</div>");
                int bLimit = Math.min(2, res.beams().size());
                for (int bi = 0; bi < bLimit; bi++) {
                    TraceAblationAttributionResult.Beam b = res.beams().get(bi);
                    sb.append(
                            "<div style=\"margin-top:8px; padding:8px; border:1px solid #e6eef8; border-radius:6px; background:#fff;\">");
                    sb.append("<div style=\"font-weight:700;\">beam #" + (bi + 1) + "</div>");
                    sb.append("<div style=\"font-size:12px; color:#666;\">score=" + b.score() + ", weight=" + b.weight()
                            + "</div>");
                    if (b.steps() != null && !b.steps().isEmpty()) {
                        sb.append("<ul style=\"margin:8px 0 0 18px;\">");
                        for (TraceAblationAttributionResult.QaStep s : b.steps()) {
                            sb.append("<li>");
                            sb.append("<b>Q</b> ").append(escape(s.question())).append("<br/>");
                            sb.append("<b>A</b> ").append(escape(s.answer()));
                            sb.append("</li>");
                        }
                        sb.append("</ul>");
                    }
                    sb.append("</div>");
                }
                sb.append("</details>");
            }

            sb.append("</div></div>");
            return sb.toString();
        } catch (Exception e) {
            // fail-soft: never break the trace UI.
            return "";
        }
    }

    private static String renderSimpleList(List<String> items) {
        if (items == null || items.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<ul style=\"margin:6px 0 0 18px; padding:0;\">");
        int limit = Math.min(8, items.size());
        for (int i = 0; i < limit; i++) {
            String it = items.get(i);
            if (it == null || it.isBlank())
                continue;
            sb.append("<li>").append(escape(it)).append("</li>");
        }
        if (items.size() > limit) {
            sb.append("<li>… (" + (items.size() - limit) + " more)</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static void appendKvGroup(StringBuilder sb, Map<String, Object> meta, java.util.Set<String> shown,
            String groupLabel, java.util.List<String> keys) {
        boolean any = false;
        for (String k : keys)
            if (meta.containsKey(k)) {
                any = true;
                break;
            }
        if (!any)
            return;
        sb.append("<tr class='trace-kv-group'><th colspan='2'>").append(escape(groupLabel)).append("</th></tr>");
        for (String k : keys)
            appendKvRow(sb, meta, shown, k);
    }

    private static void appendKvPrefixGroup(StringBuilder sb, Map<String, Object> meta, java.util.Set<String> shown,
            String groupLabel, String prefix, int limit) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : meta.keySet())
            if (k.startsWith(prefix) && !shown.contains(k)) {
                keys.add(k);
                if (keys.size() >= limit)
                    break;
            }
        if (keys.isEmpty())
            return;
        java.util.Collections.sort(keys);
        sb.append("<tr class='trace-kv-group'><th colspan='2'>").append(escape(groupLabel)).append("</th></tr>");
        for (String k : keys)
            appendKvRow(sb, meta, shown, k);
    }

    /**
     * Human-friendly rendering for selectedTerms (samples) + effectiveQuery.
     *
     * This is intentionally:
     * - compact (samples only)
     * - safe (server-side redaction was already applied upstream)
     */
    private static void appendWebSelectedTerms(StringBuilder sb, Map<String, Object> meta,
            java.util.Set<String> shown) {
        String effectiveQuery = getString(meta, "web.effectiveQuery");
        Object selectedObj = meta.get("web.selectedTerms");
        Object summaryObj = meta.get("web.selectedTerms.summary");
        Object appliedObj = meta.get("web.selectedTerms.applied");

        boolean any = isNonBlank(effectiveQuery) || selectedObj != null || summaryObj != null || appliedObj != null;
        if (!any)
            return;

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web Debug</th></tr>");

        if (isNonBlank(effectiveQuery)) {
            sb.append("<tr><th>web.effectiveQuery</th><td><code>")
                    .append(escape(effectiveQuery))
                    .append("</code></td></tr>");
            shown.add("web.effectiveQuery");
        }

        if (summaryObj != null && isNonBlank(String.valueOf(summaryObj))) {
            sb.append("<tr><th>web.selectedTerms.summary</th><td><code>")
                    .append(escape(String.valueOf(summaryObj)))
                    .append("</code></td></tr>");
            shown.add("web.selectedTerms.summary");
        }

        // If we don't have a structured representation, fall back to safeValue
        // rendering.
        if (!(selectedObj instanceof java.util.Map)) {
            if (selectedObj != null) {
                sb.append("<tr><th>web.selectedTerms</th><td><code>")
                        .append(escape(safeValue(selectedObj)))
                        .append("</code></td></tr>");
                shown.add("web.selectedTerms");
            }
            if (appliedObj != null) {
                sb.append("<tr><th>web.selectedTerms.applied</th><td><code>")
                        .append(escape(safeValue(appliedObj)))
                        .append("</code></td></tr>");
                shown.add("web.selectedTerms.applied");
            }
            return;
        }

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> selected = (java.util.Map<String, Object>) selectedObj;

        Object domainProfile = selected.get("domainProfile");
        Object countsObj = selected.get("counts");
        Object samplesObj = selected.get("samples");

        java.util.Map<String, Object> counts = (countsObj instanceof java.util.Map)
                ? (java.util.Map<String, Object>) countsObj
                : java.util.Map.of();
        java.util.Map<String, Object> samples = (samplesObj instanceof java.util.Map)
                ? (java.util.Map<String, Object>) samplesObj
                : java.util.Map.of();

        // Render as nested details with fold/unfold per category.
        sb.append("<tr><th>web.selectedTerms</th><td>");
        Object effObj = meta.get("web.effectiveQuery");
        if (effObj != null) {
            String t = String.valueOf(effObj);
            if (!t.isBlank()) {
                sb.append("<div style='margin:0 0 6px 0;'><code>effectiveQuery: ").append(escape(t))
                        .append("</code></div>");
                shown.add("web.effectiveQuery");
            }
        }
        Object summObj = meta.get("web.selectedTerms.summary");
        if (summObj != null) {
            String t = String.valueOf(summObj);
            if (!t.isBlank()) {
                sb.append("<div style='margin:0 0 6px 0;'><code>summary: ").append(escape(t)).append("</code></div>");
                shown.add("web.selectedTerms.summary");
            }
        }
        sb.append("<details class='trace-selected-terms'>");

        String dom = domainProfile == null ? "" : String.valueOf(domainProfile);
        String countsLine = counts.isEmpty() ? "" : String.valueOf(counts);
        sb.append("<summary><code>")
                .append(escape(firstNonBlank(dom, "(no domainProfile)")))
                .append(escape(isNonBlank(countsLine) ? (" " + countsLine) : ""))
                .append("</code></summary>");

        // Applied tokens (sample) first
        if (appliedObj instanceof java.util.Map appliedMap && !appliedMap.isEmpty()) {
            sb.append("<div class='trace-selected-applied'>");
            sb.append("<div class='trace-selected-label'>Applied tokens (sample)</div>");
            sb.append(renderTokenCategoryDetails("negative", appliedMap.get("negative"), counts.get("negative")));
            sb.append(renderTokenCategoryDetails("aliases", appliedMap.get("aliases"), counts.get("aliases")));
            sb.append(renderTokenCategoryDetails("domains", appliedMap.get("domains"), counts.get("domains")));
            sb.append("</div>");
            shown.add("web.selectedTerms.applied");
        }

        sb.append("<div class='trace-selected-all'>");
        sb.append("<div class='trace-selected-label'>All categories (samples)</div>");
        for (String k : java.util.List.of("exact", "must", "should", "negative", "aliases", "domains")) {
            sb.append(renderTokenCategoryDetails(k, samples.get(k), counts.get(k)));
        }
        sb.append("</div>");

        Object rulesObj = meta.get("web.selectedTerms.rules");
        java.util.List<String> rules = new java.util.ArrayList<>();
        if (rulesObj instanceof java.util.List<?> lst) {
            for (Object o : lst) {
                if (o == null)
                    continue;
                String t = String.valueOf(o).trim();
                if (t.isEmpty())
                    continue;
                if (t.length() > 220)
                    t = t.substring(0, 220) + "…";
                rules.add(t);
                if (rules.size() >= 16)
                    break;
            }
        } else if (rulesObj != null) {
            String t = String.valueOf(rulesObj).trim();
            if (!t.isEmpty()) {
                if (t.length() > 220)
                    t = t.substring(0, 220) + "…";
                rules.add(t);
            }
        }

        if (!rules.isEmpty()) {
            sb.append("<details class='trace-fold trace-selected-rules'>");
            sb.append("<summary><code>rules / evidence (" + rules.size() + ")</code></summary>");
            sb.append("<ul style='margin:6px 0 0 18px; padding:0;'>");
            for (String r : rules) {
                sb.append("<li><code>" + escape(r) + "</code></li>");
            }
            sb.append("</ul></details>");
            shown.add("web.selectedTerms.rules");
        }

        sb.append("</details>");
        sb.append("</td></tr>");
        shown.add("web.selectedTerms");
    }

    /**
     * Human-friendly debug block for Naver planHint "boost-only" overlay.
     *
     * <p>
     * UX goals:
     * <ul>
     * <li>One glance summary: applied vs skipped + reason.</li>
     * <li>Explain "location" classification (weak/strong/promoted/denied) so tuning
     * is fast.</li>
     * <li>Show current tuning knobs (suffix/minPrefix/deny) without redeploy.</li>
     * </ul>
     */
    private static void appendWebNaverPlanHintBoostOnlyOverlay(StringBuilder sb, Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        if (extraMeta == null || extraMeta.isEmpty())
            return;

        boolean any = extraMeta.containsKey("web.naver.planHintBoostOnly.decision")
                || extraMeta.containsKey("web.naver.planHintBoostOnly.applied")
                || extraMeta.containsKey("web.naver.planHintBoostOnly.skipped.reason")
                || extraMeta.containsKey("web.naver.planHintBoostOnly.count")
                || extraMeta.containsKey("web.naver.planHintBoostOnly.location.weakPromoted.token")
                || extraMeta.containsKey("web.naver.planHintBoostOnly.location.weakPromoteDenied.keyword");

        if (!any)
            return;

        String decision = safeValueOrDefault(extraMeta.get("web.naver.planHintBoostOnly.decision"), "");
        String skipReason = safeValueOrDefault(extraMeta.get("web.naver.planHintBoostOnly.skipped.reason"), "");
        boolean applied = truthy(extraMeta.get("web.naver.planHintBoostOnly.applied"));
        boolean open = (!applied) || (skipReason != null && !skipReason.isBlank())
                || (decision != null && decision.startsWith("skipped:"))
                || extraMeta.containsKey("web.naver.planHintBoostOnly.location.weakPromoted.token")
                || extraMeta.containsKey("web.naver.planHintBoostOnly.location.weakPromoteDenied.keyword");

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Naver PlanHint Boost-Only Overlay</th></tr>");
        sb.append("<tr><th>web.naver.planHintBoostOnly</th><td>");

        sb.append("<details class='trace-fold'");
        if (open)
            sb.append(" open");
        sb.append(">");

        sb.append("<summary>");
        if (decision != null && !decision.isBlank()) {
            sb.append(escape(decision));
        } else {
            sb.append(applied ? "applied" : "not-applied");
        }
        if (skipReason != null && !skipReason.isBlank() && (decision == null || !decision.contains(skipReason))) {
            sb.append(" · reason=").append(escape(skipReason));
        }
        Object cnt = extraMeta.get("web.naver.planHintBoostOnly.count");
        if (cnt != null)
            sb.append(" · count=").append(escape(String.valueOf(cnt)));
        Object sc = extraMeta.get("web.naver.planHintBoostOnly.skipped.count");
        if (sc != null)
            sb.append(" · skipped=").append(escape(String.valueOf(sc)));
        sb.append("</summary>");

        // --- main key/value block ---
        sb.append("<table class='trace-table small'>");
        sb.append("<thead><tr><th>key</th><th>value</th></tr></thead><tbody>");

        java.util.List<String> keys = java.util.List.of(
                "web.naver.planHintBoostOnly.planHintStrict",
                "web.naver.planHintBoostOnly.applyOnlyWhenPlanHintStrict",
                "web.naver.planHintBoostOnly.method",
                "web.naver.planHintBoostOnly.query",
                "web.naver.planHintBoostOnly.rid",
                "web.naver.planHintBoostOnly.sessionId",
                "web.naver.planHintBoostOnly.original.officialOnly",
                "web.naver.planHintBoostOnly.original.domainProfile",
                "web.naver.planHintBoostOnly.overlay.officialOnly",
                "web.naver.planHintBoostOnly.overlay.domainProfile",
                "web.naver.planHintBoostOnly.applied",
                "web.naver.planHintBoostOnly.decision",
                "web.naver.planHintBoostOnly.skipped.reason",

                "web.naver.planHintBoostOnly.location.localIntentHit",
                "web.naver.planHintBoostOnly.location.localIntentKeyword",
                "web.naver.planHintBoostOnly.location.negativeHit",
                "web.naver.planHintBoostOnly.location.negativeKeyword",
                "web.naver.planHintBoostOnly.location.negativeMode",
                "web.naver.planHintBoostOnly.location.strongHit",
                "web.naver.planHintBoostOnly.location.weakHit",
                "web.naver.planHintBoostOnly.location.hitStrongKeywords",
                "web.naver.planHintBoostOnly.location.hitWeakKeywords",
                "web.naver.planHintBoostOnly.location.weakOnlyIgnored",
                "web.naver.planHintBoostOnly.location.weakPromoteDenied.keyword",
                "web.naver.planHintBoostOnly.location.weakPromoted.kind",
                "web.naver.planHintBoostOnly.location.weakPromoted.token",
                "web.naver.planHintBoostOnly.location.weakPromoted.suffix",
                "web.naver.planHintBoostOnly.location.weakPromoted.prefixHangul",
                "web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.required",
                "web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.default",
                "web.naver.planHintBoostOnly.location.weakPromoted.minPrefix.usedOverride",

                "web.naver.planHintBoostOnly.config.location.weakOnlyPromote.enabled",
                "web.naver.planHintBoostOnly.config.location.weakOnlyPromote.suffixes",
                "web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixDefault",
                "web.naver.planHintBoostOnly.config.location.weakOnlyPromote.allowTokens",
                "web.naver.planHintBoostOnly.config.location.weakOnlyPromote.denyKeywords");

        for (String k : keys) {
            if (!extraMeta.containsKey(k))
                continue;
            Object v = extraMeta.get(k);
            if (v == null)
                continue;
            String vv = safeValueOrDefault(v, "");
            if (vv == null || vv.isBlank() || "null".equals(vv))
                continue;

            sb.append("<tr><th>").append(escape(k)).append("</th><td class='trace-mono'>")
                    .append(escape(truncate(vv, 800))).append("</td></tr>");
            shown.add(k);
        }
        sb.append("</tbody></table>");

        // --- minPrefixBySuffix as a table (tuning UX) ---
        Object mpObj = extraMeta.get("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixBySuffix");
        if (mpObj instanceof java.util.Map<?, ?> mm && !mm.isEmpty()) {
            sb.append("<div class='trace-sub-title'>weakOnlyPromote.minPrefixBySuffix</div>");
            sb.append("<table class='trace-table small'>");
            sb.append("<thead><tr><th>suffix</th><th>minPrefix</th></tr></thead><tbody>");
            for (java.util.Map.Entry<?, ?> e : mm.entrySet()) {
                if (e == null || e.getKey() == null)
                    continue;
                String sk = String.valueOf(e.getKey());
                String sv = (e.getValue() == null) ? "" : String.valueOf(e.getValue());
                sb.append("<tr><td class='trace-mono'>").append(escape(sk)).append("</td><td class='trace-mono'>")
                        .append(escape(sv)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
            shown.add("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixBySuffix");
        } else if (mpObj != null) {
            // fall back: render raw value
            String vv = safeValueOrDefault(mpObj, "");
            if (vv != null && !vv.isBlank() && !"null".equals(vv)) {
                sb.append("<div class='trace-sub-title'>weakOnlyPromote.minPrefixBySuffix</div>");
                sb.append("<div class='trace-mono'>").append(escape(truncate(vv, 800))).append("</div>");
                shown.add("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixBySuffix");
            }
        }

        sb.append("</details>");
        sb.append("</td></tr>");
    }

    private static String renderTokenCategoryDetails(String name, Object sampleObj, Object countObj) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        if (sampleObj instanceof java.util.List<?> lst) {
            for (Object o : lst) {
                if (o == null)
                    continue;
                tokens.add(String.valueOf(o));
                if (tokens.size() >= 12)
                    break;
            }
        } else if (sampleObj != null) {
            tokens.add(String.valueOf(sampleObj));
        }
        String count = (countObj == null) ? "" : String.valueOf(countObj);
        String header = name + (isNonBlank(count) ? (" (n=" + count + ")") : "");

        StringBuilder sb = new StringBuilder();
        sb.append("<details class='trace-selected-cat'>");
        sb.append("<summary><code>").append(escape(header)).append("</code></summary>");
        if (tokens.isEmpty()) {
            sb.append("<div class='trace-selected-empty'><code>(empty)</code></div>");
        } else {
            sb.append("<div class='trace-selected-tokens'><code>");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0)
                    sb.append("<br/>");
                sb.append(escape(tokens.get(i)));
            }
            sb.append("</code></div>");
        }
        sb.append("</details>");
        return sb.toString();
    }

    /**
     * Human-friendly rendering for web.await.events.
     *
     * <p>
     * UX goals:
     * <ul>
     * <li>Table view with a lightweight sort/filter UI.</li>
     * <li>Engine-grouped sections (fold/unfold) to make “원인/단계/엔진” pop.</li>
     * <li>Non-ok toggle (ok/done 제외) so the operator can instantly focus on
     * anomalies.</li>
     * </ul>
     *
     * <p>
     * When scripts are blocked, the raw tables are still readable.
     */
    private static void appendWebAwaitEvents(StringBuilder sb, Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        Object eventsObj = extraMeta.get("web.await.events");
        if (!(eventsObj instanceof java.util.List<?> list)) {
            return;
        }

        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof java.util.Map<?, ?> m) {
                java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    Object k = e.getKey();
                    if (k != null) {
                        mm.put(String.valueOf(k), e.getValue());
                    }
                }
                events.add(mm);
            }
        }

        if (events.isEmpty()) {
            return;
        }

        // --- summary stats ---
        long total = events.size();
        long soft = events.stream().filter(ev -> "soft".equalsIgnoreCase(String.valueOf(ev.getOrDefault("stage", ""))))
                .count();
        long hard = events.stream().filter(ev -> "hard".equalsIgnoreCase(String.valueOf(ev.getOrDefault("stage", ""))))
                .count();

        long timeoutHard = 0;
        long timeoutSoft = 0;
        long timeoutAll = 0;
        long nonOk = 0;
        Long maxWaited = null;
        for (java.util.Map<String, Object> ev : events) {
            String stage = String.valueOf(ev.getOrDefault("stage", "")).toLowerCase();
            String cause = String.valueOf(ev.getOrDefault("cause", "")).toLowerCase();
            boolean skip = cause.equals("missing_future") || cause.startsWith("skip_");
            boolean okish = cause.equals("ok") || cause.equals("done") || cause.equals("done_null") || skip;
            boolean nonOkFlag = truthy(ev.get("nonOk")) || !okish;

            boolean softTimeoutFlag = truthy(ev.get("softTimeout"));
            boolean hardTimeoutFlag = truthy(ev.get("hardTimeout"));

            // Backfill timeout kind when older traces do not carry softTimeout/hardTimeout.
            if (!softTimeoutFlag && !hardTimeoutFlag) {
                boolean stageSoft = "soft".equals(stage);
                boolean stageHard = "hard".equals(stage);
                if ("budget_exhausted".equals(cause) || "timeout_soft".equals(cause)) {
                    softTimeoutFlag = true;
                } else if ("timeout_hard".equals(cause)) {
                    hardTimeoutFlag = true;
                } else if (cause.contains("timeout")) {
                    if (stageSoft) {
                        softTimeoutFlag = true;
                    } else if (stageHard) {
                        hardTimeoutFlag = true;
                    } else {
                        // safest default: treat unknown "timeout" as hard timeout
                        hardTimeoutFlag = true;
                    }
                }
            }

            boolean timeoutAny = truthy(ev.get("timeout"))
                    || softTimeoutFlag
                    || hardTimeoutFlag
                    || cause.equals("timeout")
                    || cause.equals("budget_exhausted")
                    || cause.equals("timeout_soft")
                    || cause.equals("timeout_hard");

            if (timeoutAny) {
                timeoutAll++;
                if (softTimeoutFlag) {
                    timeoutSoft++;
                } else if (hardTimeoutFlag) {
                    timeoutHard++;
                } else if ("soft".equals(stage)) {
                    timeoutSoft++;
                } else if ("hard".equals(stage)) {
                    timeoutHard++;
                }
            }

            if (nonOkFlag)
                nonOk++;
            Long w = toLong(ev.get("waitedMs"));
            if (w != null && (maxWaited == null || w > maxWaited)) {
                maxWaited = w;
            }
        }

        String timeoutHardPct = total > 0 ? String.valueOf(Math.round((timeoutHard * 100.0) / total)) : "0";
        String timeoutAllPct = total > 0 ? String.valueOf(Math.round((timeoutAll * 100.0) / total)) : "0";
        String nonOkPct = total > 0 ? String.valueOf(Math.round((nonOk * 100.0) / total)) : "0";

        // Keep the section open when there is any anomaly signal.
        boolean open = (timeoutAll > 0) || (nonOk > 0);

        // --- group by engine ---
        java.util.Map<String, java.util.List<java.util.Map<String, Object>>> byEngine = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> ev : events) {
            String engine = safeValue(ev.get("engine"));
            if (engine == null || engine.isBlank() || engine.equals("null")) {
                engine = "(unknown)";
            }
            byEngine.computeIfAbsent(engine, k -> new java.util.ArrayList<>()).add(ev);
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web Await Events</th></tr>");
        sb.append("<tr><th>web.await.events</th><td>");

        String rootId = "trace-await-root-" + System.identityHashCode(extraMeta);

        sb.append("<details class='trace-await'");
        if (open) {
            sb.append(" open");
        }
        sb.append(">");

        sb.append("<summary>");
        sb.append("count=").append(total);
        // timeout=HARD timeout (excludes opportunistic soft timeout noise)
        sb.append(" timeout=").append(timeoutHard).append(" (").append(timeoutHardPct).append("%)");
        sb.append(" timeoutSoft=").append(timeoutSoft);
        sb.append(" timeoutAll=").append(timeoutAll).append(" (").append(timeoutAllPct).append("%)");
        sb.append(" nonOk=").append(nonOk).append(" (").append(nonOkPct).append("%)");
        sb.append(" soft=").append(soft);
        sb.append(" hard=").append(hard);
        if (maxWaited != null) {
            sb.append(" maxWaitedMs=").append(maxWaited);
        }
        sb.append("</summary>");

        sb.append("<div id='").append(rootId).append("'>");

        // controls
        sb.append("<div class='trace-await-controls'>");
        sb.append("<label><input type='checkbox' data-awf='timeoutOnly'> Timeout only</label>");
        sb.append("<label><input type='checkbox' data-awf='nonOkOnly'> non-ok only</label>");
        sb.append("<label><input type='checkbox' data-awf='softOnly'> Soft only</label>");
        sb.append("<label><input type='checkbox' data-awf='hardOnly'> Hard only</label>");

        sb.append("<label>Engine <select data-awf='engine'>");
        sb.append("<option value='*'>all</option>");
        for (String eng : byEngine.keySet()) {
            sb.append("<option value='").append(escapeAttr(eng)).append("'>").append(escape(eng)).append("</option>");
        }
        sb.append("</select></label>");

        sb.append("<label>Sort <select data-awf='sort'>");
        sb.append("<option value='none'>original</option>");
        sb.append("<option value='waitedDesc'>waitedMs desc</option>");
        sb.append("<option value='timeoutDesc'>timeoutMs desc</option>");
        sb.append("</select></label>");
        sb.append("</div>");

        // engine-grouped tables
        int globalIdx = 0;
        for (java.util.Map.Entry<String, java.util.List<java.util.Map<String, Object>>> en : byEngine.entrySet()) {
            String engine = en.getKey();
            java.util.List<java.util.Map<String, Object>> evs = en.getValue();

            long engTimeoutHard = 0;
            long engTimeoutSoft = 0;
            long engTimeoutAll = 0;
            long engNonOk = 0;
            for (java.util.Map<String, Object> ev : evs) {
                String stage = String.valueOf(ev.getOrDefault("stage", "")).toLowerCase();
                String cause = String.valueOf(ev.getOrDefault("cause", "")).toLowerCase();
                boolean skip = cause.equals("missing_future") || cause.startsWith("skip_");
                boolean okish = cause.equals("ok") || cause.equals("done") || cause.equals("done_null") || skip;
                boolean nonOkFlag = truthy(ev.get("nonOk")) || !okish;

                boolean softTimeoutFlag = truthy(ev.get("softTimeout"));
                boolean hardTimeoutFlag = truthy(ev.get("hardTimeout"));

                // Backfill timeout kind when older traces do not carry softTimeout/hardTimeout.
                if (!softTimeoutFlag && !hardTimeoutFlag) {
                    boolean stageSoft = "soft".equals(stage);
                    boolean stageHard = "hard".equals(stage);
                    if ("budget_exhausted".equals(cause) || "timeout_soft".equals(cause)) {
                        softTimeoutFlag = true;
                    } else if ("timeout_hard".equals(cause)) {
                        hardTimeoutFlag = true;
                    } else if (cause.contains("timeout")) {
                        if (stageSoft) {
                            softTimeoutFlag = true;
                        } else if (stageHard) {
                            hardTimeoutFlag = true;
                        } else {
                            hardTimeoutFlag = true;
                        }
                    }
                }

                boolean timeoutAny = truthy(ev.get("timeout"))
                        || softTimeoutFlag
                        || hardTimeoutFlag
                        || cause.equals("timeout")
                        || cause.equals("budget_exhausted")
                        || cause.equals("timeout_soft")
                        || cause.equals("timeout_hard");

                if (timeoutAny) {
                    engTimeoutAll++;
                    if (softTimeoutFlag) {
                        engTimeoutSoft++;
                    } else if (hardTimeoutFlag) {
                        engTimeoutHard++;
                    } else if ("soft".equals(stage)) {
                        engTimeoutSoft++;
                    } else if ("hard".equals(stage)) {
                        engTimeoutHard++;
                    }
                }

                if (nonOkFlag)
                    engNonOk++;
            }

            boolean engOpen = engTimeoutAll > 0 || engNonOk > 0;

            String engTimeoutPct = evs.size() > 0
                    ? String.valueOf(Math.round((engTimeoutHard * 100.0) / evs.size()))
                    : "0";
            String engTimeoutAllPct = evs.size() > 0
                    ? String.valueOf(Math.round((engTimeoutAll * 100.0) / evs.size()))
                    : "0";
            String engNonOkPct = evs.size() > 0
                    ? String.valueOf(Math.round((engNonOk * 100.0) / evs.size()))
                    : "0";

            sb.append("<details class='trace-await-engine' data-engine='").append(escapeAttr(engine)).append("'");
            if (engOpen)
                sb.append(" open");
            sb.append(">");
            sb.append("<summary>");
            sb.append(escape(engine));
            sb.append(" (count=").append(evs.size())
                    .append(", timeout=").append(engTimeoutHard).append(" (").append(engTimeoutPct).append("%)")
                    .append(", timeoutSoft=").append(engTimeoutSoft)
                    .append(", timeoutAll=").append(engTimeoutAll).append(" (").append(engTimeoutAllPct).append("%)")
                    .append(", nonOk=").append(engNonOk).append(" (").append(engNonOkPct).append("%)")
                    .append(")");
            sb.append("</summary>");

            sb.append("<table class='trace-await-table'>");
            sb.append("<thead><tr>");
            sb.append(
                    "<th>#</th><th>stage</th><th>engine</th><th>step</th><th>cause</th><th>timeoutMs</th><th>waitedMs</th><th>err</th><th>detail</th>");
            sb.append("</tr></thead>");
            sb.append("<tbody>");

            for (java.util.Map<String, Object> ev : evs) {
                globalIdx++;

                String stage = safeValue(ev.get("stage"));
                String step = safeValue(ev.get("step"));
                String cause = safeValue(ev.get("cause"));
                String timeoutMs = safeValue(ev.get("timeoutMs"));
                String waitedMs = safeValue(ev.get("waitedMs"));
                String err = safeValue(ev.get("err"));

                String causeLower = (cause == null) ? "" : cause.toLowerCase(java.util.Locale.ROOT);
                boolean skip = causeLower.equals("missing_future") || causeLower.startsWith("skip_");
                boolean okish = causeLower.equals("ok") || causeLower.equals("done") || causeLower.equals("done_null")
                        || skip;
                boolean nonOkFlag = truthy(ev.get("nonOk")) || !okish;

                String detail = firstNonBlank(
                        safeValue(ev.get("note")),
                        safeValue(ev.get("detail")),
                        safeValue(ev.get("errMsg")),
                        safeValue(ev.get("extra")));
                if (detail != null && detail.length() > 240) {
                    detail = detail.substring(0, 240) + "...";
                }

                sb.append("<tr data-ord='").append(globalIdx)
                        .append("' data-stage='").append(escapeAttr(String.valueOf(stage).toLowerCase()))
                        .append("' data-engine='").append(escapeAttr(engine))
                        .append("' data-cause='").append(escapeAttr(String.valueOf(cause).toLowerCase()))
                        .append("' data-nonok='").append(nonOkFlag ? "1" : "0")
                        .append("'>");

                sb.append("<td>").append(globalIdx).append("</td>");
                sb.append("<td>").append(escape(stage)).append("</td>");
                sb.append("<td>").append(escape(engine)).append("</td>");
                sb.append("<td>").append(escape(step)).append("</td>");
                sb.append("<td>").append(escape(cause)).append("</td>");
                sb.append("<td>").append(escape(timeoutMs)).append("</td>");
                sb.append("<td>").append(escape(waitedMs)).append("</td>");
                sb.append("<td>").append(escape(err)).append("</td>");
                sb.append("<td><code>").append(escape(detail)).append("</code></td>");
                sb.append("</tr>");
            }

            sb.append("</tbody></table>");
            sb.append("</details>");
        }

        // JS: filter/sort across all grouped tables
        sb.append(
                """
                        <script data-trace-script="1">
                        (function(){
                          var root=document.getElementById('%s');
                          if(!root) return;

                          var cbTimeout=root.querySelector('[data-awf="timeoutOnly"]');
                          var cbNonOk=root.querySelector('[data-awf="nonOkOnly"]');
                          var cbSoft=root.querySelector('[data-awf="softOnly"]');
                          var cbHard=root.querySelector('[data-awf="hardOnly"]');
                          var selEngine=root.querySelector('[data-awf="engine"]');
                          var selSort=root.querySelector('[data-awf="sort"]');

                          function allRows(){
                            return Array.prototype.slice.call(root.querySelectorAll('tbody tr'));
                          }

                          function applyFilter(){
                            var timeoutOnly=!!(cbTimeout && cbTimeout.checked);
                            var nonOkOnly=!!(cbNonOk && cbNonOk.checked);
                            var softOnly=!!(cbSoft && cbSoft.checked);
                            var hardOnly=!!(cbHard && cbHard.checked);
                            var eng=selEngine ? (selEngine.value || '*') : '*';

                            var stageMode=(softOnly && !hardOnly) ? 'soft' : ((!softOnly && hardOnly) ? 'hard' : 'any');

                            var rows=allRows();
                            rows.forEach(function(r){
                              var stage=(r.getAttribute('data-stage')||'').toLowerCase();
                              var cause=(r.getAttribute('data-cause')||'').toLowerCase();
                              var engine=(r.getAttribute('data-engine')||'');
                              var nonok=(r.getAttribute('data-nonok')||'0')==='1';

                              var ok=true;
                              if(timeoutOnly){
                                ok=(cause==='timeout' || cause==='budget_exhausted' || cause==='timeout_soft' || cause==='timeout_hard');
                              }
                              if(nonOkOnly){
                                ok=ok && nonok;
                              }
                              if(stageMode!=='any'){
                                ok=ok && (stage===stageMode);
                              }
                              if(eng!=='*'){
                                ok=ok && (engine===eng);
                              }
                              r.style.display=ok ? '' : 'none';
                            });

                            // hide empty engine groups
                            var groups=Array.prototype.slice.call(root.querySelectorAll('details.trace-await-engine'));
                            groups.forEach(function(g){
                              var anyVisible=false;
                              Array.prototype.slice.call(g.querySelectorAll('tbody tr')).forEach(function(r){
                                if(r.style.display!=='none') anyVisible=true;
                              });
                              g.style.display=anyVisible ? '' : 'none';
                              if(eng!=='*' && g.getAttribute('data-engine')===eng){
                                g.open=true;
                              }
                            });
                          }

                          function sortBody(body, key){
                            var rows=Array.prototype.slice.call(body.querySelectorAll('tr'));
                            if(key==='none'){
                              rows.sort(function(a,b){
                                var ao=parseInt(a.getAttribute('data-ord')||'0',10)||0;
                                var bo=parseInt(b.getAttribute('data-ord')||'0',10)||0;
                                return ao-bo;
                              });
                            } else if(key==='waitedDesc'){
                              rows.sort(function(a,b){
                                var aw=parseInt((a.children[6] && a.children[6].textContent) || '0',10) || 0;
                                var bw=parseInt((b.children[6] && b.children[6].textContent) || '0',10) || 0;
                                return bw-aw;
                              });
                            } else if(key==='timeoutDesc'){
                              rows.sort(function(a,b){
                                var at=parseInt((a.children[5] && a.children[5].textContent) || '0',10) || 0;
                                var bt=parseInt((b.children[5] && b.children[5].textContent) || '0',10) || 0;
                                return bt-at;
                              });
                            }
                            rows.forEach(function(r){ body.appendChild(r); });
                          }

                          function applySort(){
                            var mode=selSort ? (selSort.value || 'none') : 'none';
                            Array.prototype.slice.call(root.querySelectorAll('table.trace-await-table tbody')).forEach(function(body){
                              sortBody(body, mode);
                            });
                          }

                          [cbTimeout, cbNonOk, cbSoft, cbHard].forEach(function(el){ if(el) el.addEventListener('change', function(){ applyFilter(); }); });
                          if(selEngine) selEngine.addEventListener('change', function(){ applyFilter(); });
                          if(selSort) selSort.addEventListener('change', function(){ applySort(); applyFilter(); });

                          applySort();
                          applyFilter();
                        })();
                        </script>
                        """
                        .formatted(rootId));

        sb.append("</div>");
        sb.append("</details>");
        sb.append("</td></tr>");

        shown.add("web.await.events");
        shown.add("web.await.last");
    }

    /**
     * Human-friendly rendering for web.failsoft.runs (per-run summary).
     *
     * <p>
     * This helps when a single request triggers multiple searches (canonical +
     * extraSearchCalls),
     * because simple web.failsoft.* keys can be overwritten.
     */
    private static void appendWebFailSoftRuns(StringBuilder sb, Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        Object runsObj = extraMeta.get("web.failsoft.runs");
        if (!(runsObj instanceof java.util.List<?> list)) {
            return;
        }

        java.util.List<java.util.Map<String, Object>> runs = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof java.util.Map<?, ?> m) {
                java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    Object k = e.getKey();
                    if (k != null) {
                        mm.put(String.valueOf(k), e.getValue());
                    }
                }
                runs.add(mm);
            }
        }

        if (runs.isEmpty()) {
            return;
        }

        int outZeroCount = 0;
        int fallbackCount = 0;
        for (java.util.Map<String, Object> r : runs) {
            Long out = toLong(r.get("outCount"));
            if (out != null && out == 0) {
                outZeroCount++;
            }
            Object fb = r.get("starvationFallback");
            if (fb != null && !String.valueOf(fb).isBlank()) {
                fallbackCount++;
            }
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web FailSoft Runs</th></tr>");
        sb.append("<tr><th>web.failsoft.runs</th><td>");

        sb.append("<details class='trace-failsoft-runs'");
        if (outZeroCount > 0 || fallbackCount > 0 || runs.size() > 1) {
            sb.append(" open");
        }
        sb.append(">");

        sb.append("<summary>");
        sb.append("runs=").append(runs.size());
        if (fallbackCount > 0) {
            sb.append(" fallback=").append(fallbackCount);
        }
        if (outZeroCount > 0) {
            sb.append(" outZero=").append(outZeroCount);
        }
        sb.append("</summary>");

        sb.append("<table class='trace-await-table'>");
        sb.append("<thead><tr>");
        sb.append(
                "<th>#</th><th>runId</th><th>executedQuery</th><th>outCount</th><th>officialOnly</th><th>minCitations</th><th>clamped</th><th>fallback</th><th>stageCountsSelected</th><th>opts</th><th>candidates</th>");
        sb.append("</tr></thead>");
        sb.append("<tbody>");

        int idx = 0;
        for (java.util.Map<String, Object> r : runs) {
            idx++;
            String runId = safeValueOrDefault(r.get("runId"), "");
            String q = safeValueOrDefault(r.get("executedQuery"), safeValueOrDefault(r.get("canonicalQuery"), ""));
            if (q != null && q.length() > 120) {
                q = q.substring(0, 120) + "...";
            }
            String outCount = safeValueOrDefault(r.get("outCount"), "");
            String officialOnly = safeValueOrDefault(r.get("officialOnly"), "");
            String minCitations = safeValueOrDefault(r.get("minCitations"), "");
            String clamped = safeValueOrDefault(r.get("stageOrderClamped"), "");
            String fallback = safeValueOrDefault(r.get("starvationFallback"), "");
            String stageSel = safeValueOrDefault(r.get("stageCountsSelected"), "");

            String incDev = safeValueOrDefault(r.get("officialOnlyClampIncludeDevCommunity"), "");
            String fbEnabled = safeValueOrDefault(r.get("starvationFallbackEnabled"), "");
            String fbTrigger = safeValueOrDefault(r.get("starvationFallbackTrigger"), "");
            String fbMax = safeValueOrDefault(r.get("starvationFallbackMax"), "");
            String fbIntentAllowed = safeValueOrDefault(r.get("starvationFallbackIntentAllowed"), "");

            // Derived KPI helpers (log-parse stable)
            String executedFull = safeValueOrDefault(r.get("executedQuery"),
                    safeValueOrDefault(r.get("canonicalQuery"), ""));
            String canonicalFull = safeValueOrDefault(r.get("canonicalQuery"), "");
            String runKind = (!canonicalFull.isBlank() && !executedFull.isBlank()
                    && !executedFull.equals(canonicalFull))
                            ? "extraSearch"
                            : "canonical";

            String citeablePolicy = safeValueOrDefault(r.get("minCitationsCiteablePolicyEffective"),
                    safeValueOrDefault(r.get("minCitationsCiteablePolicy"), ""));
            String devBoosted = safeValueOrDefault(r.get("minCitationsDevCommunityBoostedCount"), "");

            long nsCount = 0L;
            Object scObj = r.get("stageCountsSelectedFromOut");
            if (scObj instanceof java.util.Map<?, ?> sm) {
                Object v = sm.get("NOFILTER_SAFE");
                Long lv = toLong(v);
                if (lv != null) {
                    nsCount = lv;
                }
            } else {
                String scs = safeValueOrDefault(r.get("stageCountsSelected"), "");
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("NOFILTER_SAFE=([0-9]+)").matcher(scs);
                if (mm.find()) {
                    try {
                        nsCount = Long.parseLong(mm.group(1));
                    } catch (Exception ignore) {
                    }
                }
            }
            long outN = 0L;
            try {
                Long oo = toLong(r.get("outCount"));
                outN = (oo == null) ? 0L : oo;
            } catch (Exception ignore) {
            }
            String nsRatio = "0.00";
            if (outN > 0L) {
                double rr = (double) nsCount / (double) outN;
                nsRatio = String.format(java.util.Locale.ROOT, "%.2f", rr);
            }

            String opts = "incDevCommunity=" + incDev + ", fbEnabled=" + fbEnabled + ", trigger=" + fbTrigger
                    + ", max=" + fbMax + ", intentAllowed=" + fbIntentAllowed
                    + ", kind=" + runKind
                    + ", citeablePolicy=" + citeablePolicy
                    + ", devBoosted=" + devBoosted
                    + ", nofilterSafeRatio=" + nsRatio;

            sb.append("<tr>");
            sb.append("<td>").append(idx).append("</td>");
            sb.append("<td>").append(escape(runId)).append("</td>");
            sb.append("<td><code>").append(escape(q)).append("</code></td>");
            sb.append("<td>").append(escape(outCount)).append("</td>");
            sb.append("<td>").append(escape(officialOnly)).append("</td>");
            sb.append("<td>").append(escape(minCitations)).append("</td>");
            sb.append("<td>").append(escape(clamped)).append("</td>");
            sb.append("<td><code>").append(escape(fallback)).append("</code></td>");
            sb.append("<td><code>").append(escape(stageSel)).append("</code></td>");

            sb.append("<td><code>").append(escape(opts)).append("</code></td>");

            // Candidate-level details (score/label/dropReason + evidence)
            sb.append("<td>");
            Object cObj = r.get("candidates");
            if (cObj instanceof java.util.List<?> candList && !candList.isEmpty()) {
                sb.append("<details><summary>");
                sb.append("cands=").append(candList.size());
                sb.append("</summary>");

                sb.append("<table class='trace-await-table'>");
                sb.append("<thead><tr>");
                sb.append(
                        "<th>idx</th><th>stage</th><th>stageFinal</th><th>baseStage</th><th>cred</th><th>score</th><th>tokenHits</th><th>negHits</th><th>selected</th><th>dropReason</th><th>override</th><th>rule</th><th>url</th>");
                sb.append("</tr></thead>");
                sb.append("<tbody>");

                for (Object co : candList) {
                    if (!(co instanceof java.util.Map<?, ?> cm)) {
                        continue;
                    }
                    String cIdx = safeValueOrDefault(cm.get("idx"), "");
                    String cStage = safeValueOrDefault(cm.get("stage"), "");
                    String cStageFinal = safeValueOrDefault(cm.get("stageFinal"), "");
                    String cBaseStage = safeValueOrDefault(cm.get("baseStage"), "");
                    String cCred = safeValueOrDefault(cm.get("cred"), safeValueOrDefault(cm.get("credibility"), ""));
                    String cScore = safeValueOrDefault(cm.get("score"), "");
                    String cTokenHits = safeValueOrDefault(cm.get("tokenHits"), "");
                    String cNegHits = safeValueOrDefault(cm.get("negHits"), "");
                    String cSelected = safeValueOrDefault(cm.get("selected"), "");
                    String cDrop = safeValueOrDefault(cm.get("dropReason"), "");
                    String cOverride = safeValueOrDefault(cm.get("overridePath"), "");
                    String cRule = safeValueOrDefault(cm.get("rule"), "");
                    String cUrl = safeValueOrDefault(cm.get("url"), "");

                    if (cUrl != null && cUrl.length() > 80) {
                        cUrl = cUrl.substring(0, 80) + "...";
                    }

                    sb.append("<tr>");
                    sb.append("<td>").append(escape(cIdx)).append("</td>");
                    sb.append("<td>").append(escape(cStage)).append("</td>");
                    sb.append("<td>").append(escape(cStageFinal)).append("</td>");
                    sb.append("<td>").append(escape(cBaseStage)).append("</td>");
                    sb.append("<td>").append(escape(cCred)).append("</td>");
                    sb.append("<td>").append(escape(cScore)).append("</td>");
                    sb.append("<td><code>").append(escape(cTokenHits)).append("</code></td>");
                    sb.append("<td><code>").append(escape(cNegHits)).append("</code></td>");
                    sb.append("<td>").append(escape(cSelected)).append("</td>");
                    sb.append("<td>").append(escape(cDrop)).append("</td>");
                    sb.append("<td>").append(escape(cOverride)).append("</td>");
                    sb.append("<td><code>").append(escape(cRule)).append("</code></td>");
                    sb.append("<td><code>").append(escape(cUrl)).append("</code></td>");
                    sb.append("</tr>");
                }

                sb.append("</tbody></table>");
                sb.append("</details>");
            }
            sb.append("</td>");
            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        sb.append("</details>");
        sb.append("</td></tr>");

        shown.add("web.failsoft.runs");
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n)
            return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendKvRow(StringBuilder sb, Map<String, Object> meta, java.util.Set<String> shown,
            String key) {
        Object v = meta.get(key);
        if (v == null)
            return;
        sb.append("<tr><th>").append(escape(key)).append("</th><td><code>").append(escape(safeValue(v)))
                .append("</code></td></tr>");
        shown.add(key);
    }

    private static String safeValue(Object v) {
        if (v == null)
            return "null";
        String s = String.valueOf(v).replace("\n", " ").trim();
        return s.length() > 800 ? s.substring(0, 800) + "..." : s;
    }

    private static String safeValueOrDefault(Object v, String defaultValue) {
        if (v == null)
            return defaultValue;
        String s = safeValue(v);
        return "null".equals(s) || s.isBlank() ? defaultValue : s;
    }

    private static boolean truthy(Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean b)
            return b;
        return String.valueOf(v).equalsIgnoreCase("true");
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n)
            return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendMinCitationsExplainRow(StringBuilder sb, Map<String, Object> meta,
            java.util.Set<String> shown) {
        Integer req = toInt(meta.get("guard.minCitations.required"));
        Integer act = toInt(meta.get("guard.minCitations.actual"));
        if (req != null && act != null && act < req) {
            String msg = "actual(" + act + ") < required(" + req + ") : min citations not met";
            sb.append("<tr class='trace-kv-row-high'><th>explain</th><td><code>").append(escape(msg))
                    .append("</code></td></tr>");
        }
    }

    private static String getString(Map<String, Object> meta, String key) {
        return meta == null ? null : (String.valueOf(meta.get(key)));
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank() && !s.equals("null");
    }

    private static boolean containsIgnoreCase(String s, String n) {
        return s != null && n != null && s.toLowerCase().contains(n.toLowerCase());
    }

    private static boolean matchesAnyCsvSubstring(String haystack, String csv) {
        if (!isNonBlank(haystack) || !isNonBlank(csv))
            return false;
        String h = haystack.toLowerCase();
        for (String t : csv.split(",")) {
            if (t == null)
                continue;
            String n = t.trim().toLowerCase();
            if (n.isEmpty())
                continue;
            if (h.contains(n))
                return true;
        }
        return false;
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss)
            if (isNonBlank(s))
                return s;
        return null;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("\"", "&quot;");
    }

    // MERGE_HOOK:PROJ_AGENT::CTX_MISSING_EVENTS_RENDER
    private static void appendCtxMissingEvents(StringBuilder sb, java.util.Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        if (sb == null || extraMeta == null || extraMeta.isEmpty())
            return;

        Object evObj = extraMeta.get("ctx.propagation.missing.events");
        if (!(evObj instanceof Iterable<?> it))
            return;

        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        for (Object o : it) {
            if (o == null)
                continue;
            if (o instanceof java.util.Map<?, ?> m) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() == null)
                        continue;
                    row.put(String.valueOf(e.getKey()), e.getValue());
                }
                events.add(row);
            } else {
                events.add(java.util.Map.of("event", String.valueOf(o)));
            }
        }
        if (events.isEmpty())
            return;

        int missingCount = 0;
        int generatedCount = 0;
        int mdcBridgeCount = 0;
        for (java.util.Map<String, Object> ev : events) {
            String kind = String.valueOf(ev.getOrDefault("kind", "")).toLowerCase();
            if (kind.contains("generated"))
                generatedCount++;
            else if (kind.contains("mdc"))
                mdcBridgeCount++;
            else if (kind.contains("missing"))
                missingCount++;
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Context Propagation Events</th></tr>");
        sb.append("<tr><th>ctx.propagation.missing.events</th><td>");
        sb.append("<details").append((missingCount > 0 || generatedCount > 0 || mdcBridgeCount > 0) ? " open" : "")
                .append(">");
        sb.append("<summary>");
        sb.append("events=").append(events.size());
        if (missingCount > 0)
            sb.append(" · missing=").append(missingCount);
        if (generatedCount > 0)
            sb.append(" · generated=").append(generatedCount);
        if (mdcBridgeCount > 0)
            sb.append(" · mdcBridge=").append(mdcBridgeCount);
        sb.append("</summary>");

        sb.append("<table class='trace-table small'>");
        sb.append("<thead><tr>")
                .append("<th>#</th><th>ts</th><th>kind</th><th>where</th><th>rid</th><th>sid</th><th>detail</th>")
                .append("</tr></thead><tbody>");

        int i = 0;
        for (java.util.Map<String, Object> ev : events) {
            i++;
            Object seqObj = ev.get("seq");
            String seq = (seqObj != null && !String.valueOf(seqObj).equals("null")) ? String.valueOf(seqObj)
                    : String.valueOf(i);

            String ts = String.valueOf(ev.getOrDefault("ts", ""));
            if (ts.length() > 30)
                ts = ts.substring(0, 30);

            String kind = safeValueOrDefault(ev.get("kind"), "");
            String where = firstNonBlank(
                    safeValueOrDefault(ev.get("where"), null),
                    safeValueOrDefault(ev.get("source"), null),
                    safeValueOrDefault(ev.get("phase"), null),
                    "");

            String rid = safeValueOrDefault(ev.get("rid"), "");
            String sid = safeValueOrDefault(ev.get("sid"), "");

            String method = safeValueOrDefault(ev.get("method"), "");
            String url = safeValueOrDefault(ev.get("url"), "");
            String detail = (isNonBlank(method) || isNonBlank(url)) ? (method + " " + url).trim() : "";
            if (!isNonBlank(detail)) {
                Object reason = ev.get("reason");
                if (reason != null)
                    detail = safeValue(reason);
            }

            sb.append("<tr>");
            sb.append("<td><code>").append(escape(seq)).append("</code></td>");
            sb.append("<td><code>").append(escape(ts)).append("</code></td>");
            sb.append("<td><code>").append(escape(kind)).append("</code></td>");
            sb.append("<td><code>").append(escape(where)).append("</code></td>");
            sb.append("<td><code>").append(escape(rid)).append("</code></td>");
            sb.append("<td><code>").append(escape(sid)).append("</code></td>");
            sb.append("<td><code>").append(escape(detail)).append("</code></td>");
            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        sb.append("</details>");
        sb.append("</td></tr>");

        shown.add("ctx.propagation.missing.events");
    }

    // MERGE_HOOK:PROJ_AGENT::ML_ROUTER_EVENTS_RENDER
    private static void appendMlRouterEvents(StringBuilder sb, java.util.Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        if (sb == null || extraMeta == null || extraMeta.isEmpty())
            return;

        Object evObj = extraMeta.get("ml.router.events");
        if (!(evObj instanceof Iterable<?> it))
            return;

        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        for (Object o : it) {
            if (o == null)
                continue;
            if (o instanceof java.util.Map<?, ?> m) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() == null)
                        continue;
                    row.put(String.valueOf(e.getKey()), e.getValue());
                }
                events.add(row);
            } else {
                events.add(java.util.Map.of("event", String.valueOf(o)));
            }
        }
        if (events.isEmpty())
            return;

        int warnish = 0;
        java.util.Map<String, Integer> byEvent = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> ev : events) {
            String e = safeValueOrDefault(ev.get("event"), safeValueOrDefault(ev.get("kind"), ""));
            String k = (e == null) ? "" : e.toLowerCase(java.util.Locale.ROOT);
            if (k.contains("blocked") || k.contains("ignored") || k.contains("fail") || k.contains("error")) {
                warnish++;
            }
            if (isNonBlank(e)) {
                byEvent.put(e, byEvent.getOrDefault(e, 0) + 1);
            }
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Model Router Events</th></tr>");
        sb.append("<tr><th>ml.router.events</th><td>");
        sb.append("<details").append(warnish > 0 ? " open" : "").append(">");
        sb.append("<summary>");
        sb.append("events=").append(events.size());
        if (!byEvent.isEmpty()) {
            int shownKinds = 0;
            for (java.util.Map.Entry<String, Integer> en : byEvent.entrySet()) {
                if (shownKinds++ >= 4)
                    break;
                sb.append(" · ").append(escape(en.getKey())).append("=").append(en.getValue());
            }
            if (byEvent.size() > 4) {
                sb.append(" · ...");
            }
        }
        sb.append("</summary>");

        sb.append("<table class='trace-table small'>");
        sb.append("<thead><tr>")
                .append("<th>#</th><th>ts</th><th>event</th><th>requested/high</th><th>selected</th><th>reason</th><th>detail</th>")
                .append("</tr></thead><tbody>");

        int idx = 0;
        for (java.util.Map<String, Object> ev : events) {
            idx++;
            Object seqObj = ev.get("seq");
            String seq = (seqObj != null && !String.valueOf(seqObj).equals("null")) ? String.valueOf(seqObj)
                    : String.valueOf(idx);

            String ts = safeValueOrDefault(ev.get("ts"), "");
            if (ts.length() > 30)
                ts = ts.substring(0, 30);

            String event = firstNonBlank(
                    safeValueOrDefault(ev.get("event"), null),
                    safeValueOrDefault(ev.get("kind"), null),
                    safeValueOrDefault(ev.get("step"), null),
                    "");

            String requested = firstNonBlank(
                    safeValueOrDefault(ev.get("requestedModel"), null),
                    safeValueOrDefault(ev.get("highModel"), null),
                    safeValueOrDefault(ev.get("model"), null),
                    "");

            String selected = firstNonBlank(
                    safeValueOrDefault(ev.get("selected"), null),
                    safeValueOrDefault(ev.get("baseModel"), null),
                    safeValueOrDefault(ev.get("baseName"), null),
                    "");

            String reason = safeValueOrDefault(ev.get("reason"), "");

            String intent = safeValueOrDefault(ev.get("intent"), "");
            String tier = safeValueOrDefault(ev.get("tier"), "");
            String error = safeValueOrDefault(ev.get("error"), "");
            String where = safeValueOrDefault(ev.get("where"), "");

            StringBuilder detail = new StringBuilder();
            if (isNonBlank(intent))
                detail.append("intent=").append(intent);
            if (isNonBlank(tier)) {
                if (detail.length() > 0)
                    detail.append(" ");
                detail.append("tier=").append(tier);
            }
            if (isNonBlank(error)) {
                if (detail.length() > 0)
                    detail.append(" ");
                detail.append("error=").append(error);
            }
            if (isNonBlank(where)) {
                if (detail.length() > 0)
                    detail.append(" ");
                detail.append("where=").append(where);
            }
            String detailStr = detail.toString();
            if (detailStr.length() > 240)
                detailStr = detailStr.substring(0, 240) + "...";

            String rowId = "ml-router-ev-" + escapeAttr(seq);

            sb.append("<tr id='").append(rowId).append("'>");
            sb.append("<td><a href='#").append(rowId).append("'><code>").append(escape(seq))
                    .append("</code></a></td>");
            sb.append("<td><code>").append(escape(ts)).append("</code></td>");
            sb.append("<td><code>").append(escape(event)).append("</code></td>");
            sb.append("<td><code>").append(escape(requested)).append("</code></td>");
            sb.append("<td><code>").append(escape(selected)).append("</code></td>");
            sb.append("<td><code>").append(escape(reason)).append("</code></td>");

            // Detail cell: click to expand full event payload.
            sb.append("<td>");
            sb.append("<details class='trace-fold'>");
            sb.append("<summary><code>").append(escape(isNonBlank(detailStr) ? detailStr : "..."))
                    .append("</code></summary>");
            sb.append("<div class='trace-kv'>");
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (Object k : ev.keySet()) {
                if (k == null)
                    continue;
                keys.add(String.valueOf(k));
            }
            java.util.Collections.sort(keys);
            for (String k : keys) {
                Object v = ev.get(k);
                sb.append("<div><code>").append(escape(k)).append("</code>: <span class='trace-mono'>")
                        .append(escape(safeValue(v))).append("</span></div>");
            }
            sb.append("</div>");
            sb.append("</details>");
            sb.append("</td>");

            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        sb.append("</details>");
        sb.append("</td></tr>");

        shown.add("ml.router.events");
    }

    // MERGE_HOOK:PROJ_AGENT::PROMPT_EVENTS_RENDER
    private static void appendPromptEvents(StringBuilder sb, java.util.Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        if (sb == null || extraMeta == null || extraMeta.isEmpty())
            return;

        Object evObj = extraMeta.get("prompt.events");
        if (!(evObj instanceof Iterable<?> it))
            return;

        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        for (Object o : it) {
            if (o == null)
                continue;
            if (o instanceof java.util.Map<?, ?> m) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() == null)
                        continue;
                    row.put(String.valueOf(e.getKey()), e.getValue());
                }
                events.add(row);
            } else {
                events.add(java.util.Map.of("step", String.valueOf(o)));
            }
        }
        if (events.isEmpty())
            return;

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Prompt Build Events</th></tr>");
        sb.append("<tr><th>prompt.events</th><td>");
        sb.append("<details open>");
        sb.append("<summary>events=").append(events.size()).append("</summary>");

        sb.append("<table class='trace-table small'>");
        sb.append("<thead><tr>")
                .append("<th>#</th><th>ts</th><th>step</th><th>web</th><th>rag</th><th>local</th><th>memory</th><th>verbosity</th><th>intent</th><th>domain</th><th>detail</th>")
                .append("</tr></thead><tbody>");

        int idx = 0;
        for (java.util.Map<String, Object> ev : events) {
            idx++;
            Object seqObj = ev.get("seq");
            String seq = (seqObj != null && !String.valueOf(seqObj).equals("null")) ? String.valueOf(seqObj)
                    : String.valueOf(idx);

            String ts = safeValueOrDefault(ev.get("ts"), "");
            if (ts.length() > 30)
                ts = ts.substring(0, 30);

            String step = firstNonBlank(
                    safeValueOrDefault(ev.get("step"), null),
                    safeValueOrDefault(ev.get("event"), null),
                    "");

            String webCount = safeValueOrDefault(ev.get("webCount"), "");
            String ragCount = safeValueOrDefault(ev.get("ragCount"), "");
            String localCount = safeValueOrDefault(ev.get("localDocsCount"), "");
            String mem = safeValueOrDefault(ev.get("memoryPresent"), "");
            String verbosity = safeValueOrDefault(ev.get("verbosity"), "");
            String intent = safeValueOrDefault(ev.get("intent"), "");
            String domain = safeValueOrDefault(ev.get("domain"), "");

            String rowId = "prompt-ev-" + escapeAttr(seq);

            sb.append("<tr id='").append(rowId).append("'>");
            sb.append("<td><a href='#").append(rowId).append("'><code>").append(escape(seq))
                    .append("</code></a></td>");
            sb.append("<td><code>").append(escape(ts)).append("</code></td>");
            sb.append("<td><code>").append(escape(step)).append("</code></td>");
            sb.append("<td>").append(escape(webCount)).append("</td>");
            sb.append("<td>").append(escape(ragCount)).append("</td>");
            sb.append("<td>").append(escape(localCount)).append("</td>");
            sb.append("<td>").append(escape(mem)).append("</td>");
            sb.append("<td>").append(escape(verbosity)).append("</td>");
            sb.append("<td>").append(escape(intent)).append("</td>");
            sb.append("<td>").append(escape(domain)).append("</td>");

            sb.append("<td>");
            sb.append("<details class='trace-fold'>");
            sb.append("<summary><code>payload</code></summary>");
            sb.append("<div class='trace-kv'>");
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (Object k : ev.keySet()) {
                if (k == null)
                    continue;
                keys.add(String.valueOf(k));
            }
            java.util.Collections.sort(keys);
            for (String k : keys) {
                Object v = ev.get(k);
                sb.append("<div><code>").append(escape(k)).append("</code>: <span class='trace-mono'>")
                        .append(escape(safeValue(v))).append("</span></div>");
            }
            sb.append("</div>");
            sb.append("</details>");
            sb.append("</td>");

            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        sb.append("</details>");
        sb.append("</td></tr>");

        shown.add("prompt.events");
    }

    // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_RENDER
    private static void appendOrchPartsTable(StringBuilder sb, java.util.Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        if (sb == null || extraMeta == null || extraMeta.isEmpty())
            return;

        Object summaryObj = extraMeta.get("orch.parts.summary");
        Object tableObj = extraMeta.get("orch.parts.table");
        if (summaryObj == null && tableObj == null)
            return;

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Parts Build-up</th></tr>");

        if (summaryObj != null) {
            shown.add("orch.parts.summary");
            sb.append("<tr><th>orch.parts.summary</th><td><code>")
                    .append(escape(String.valueOf(summaryObj)))
                    .append("</code></td></tr>");
        }

        if (tableObj instanceof Iterable<?> it) {
            shown.add("orch.parts.table");
            sb.append("<tr><th>orch.parts.table</th><td>");
            sb.append("<details open><summary>rows</summary>");
            for (Object row : it) {
                if (row == null)
                    continue;
                sb.append("<div><code>")
                        .append(escape(String.valueOf(row)))
                        .append("</code></div>");
            }
            sb.append("</details>");
            sb.append("</td></tr>");
        }
    }

    private String renderPills(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty())
            return "";
        List<String> pills = new ArrayList<>();

        if (truthy(extraMeta.get("dbg.search.enabled"))) {
            pills.add(pill("DBG", "info"));
        }
        if (truthy(extraMeta.get("uaw.ablation.bridge"))) {
            pills.add(pill("AblationBridge", "info"));
        }
        if (truthy(extraMeta.get("uaw.ablation.finalized")) || truthy(extraMeta.get("ablation.finalized"))) {
            pills.add(pill("Finalized", "ok"));
        }
        Object scoreObj = extraMeta.get("ablation.score");
        if (scoreObj != null) {
            String s = truncate(String.valueOf(scoreObj), 64);
            pills.add(pill("AblScore:" + s, "warn"));
        }
        boolean noiseEscape = truthy(extraMeta.get("qtx.noise.escape.used"))
                || truthy(extraMeta.get("qtx.noiseEscape"))
                || truthy(extraMeta.get("orch.noiseEscape.used"))
                || truthy(extraMeta.get("orch.noiseEscape.bypassSilentFailure"))
                || truthy(extraMeta.get("aux.noiseOverride"))
                || truthy(extraMeta.get("keywordSelection.noiseEscape"))
                || truthy(extraMeta.get("keywordSelection.bypass.noiseEscape"))
                || truthy(extraMeta.get("disambiguation.noiseEscape"));
        if (noiseEscape) {
            pills.add(pill("NoiseEscape", "warn"));

            // Naver planHint "boost-only" overlay (debug UX / quick glance)
            String nDecision = getString(extraMeta, "web.naver.planHintBoostOnly.decision");
            String nSkip = getString(extraMeta, "web.naver.planHintBoostOnly.skipped.reason");
            boolean nApplied = truthy(extraMeta.get("web.naver.planHintBoostOnly.applied"));
            if (isNonBlank(nDecision)) {
                String s = truncate(nDecision, 32);
                if (nDecision.startsWith("applied")) {
                    pills.add(pill("NaverOverlay", "ok"));
                } else if (nDecision.startsWith("skipped:")) {
                    pills.add(pill("NaverOverlay:" + s, "warn"));
                } else {
                    pills.add(pill("NaverOverlay:" + s, "info"));
                }
            } else if (isNonBlank(nSkip)) {
                pills.add(pill("NaverOverlaySkip:" + truncate(nSkip, 24), "warn"));
            } else if (nApplied) {
                pills.add(pill("NaverOverlay", "ok"));
            }

            String weakPromoted = getString(extraMeta, "web.naver.planHintBoostOnly.location.weakPromoted.token");
            if (isNonBlank(weakPromoted)) {
                pills.add(pill("LocWeakPromoted", "warn"));
            }
            String promoteDenied = getString(extraMeta,
                    "web.naver.planHintBoostOnly.location.weakPromoteDenied.keyword");
            if (isNonBlank(promoteDenied)) {
                pills.add(pill("LocPromoteDenied", "info"));
            }
            if (truthy(extraMeta.get("web.naver.planHintBoostOnly.location.weakOnlyIgnored"))) {
                pills.add(pill("LocWeakOnlyIgnored", "info"));
            }

        }

        // Context propagation leakage: make it visible at a glance.
        boolean ctxMissing = truthy(extraMeta.get("ctx.propagation.missing"))
                || truthy(extraMeta.get("ctx.correlation.missing"))
                || truthy(extraMeta.get("ctx.mdc.bridge"));
        Integer ctxMissingCount = toInt(extraMeta.get("ctx.propagation.missing.count"));
        if (ctxMissingCount == null) {
            ctxMissingCount = toInt(extraMeta.get("ctx.correlation.missing.count"));
        }
        // Heuristic fallback (when ctx.* anchors were not recorded)
        if (!ctxMissing) {
            String sid = getString(extraMeta, "sid");
            String rid = firstNonBlank(
                    getString(extraMeta, "x-request-id"),
                    getString(extraMeta, "requestId"),
                    getString(extraMeta, "trace"),
                    getString(extraMeta, "traceId"),
                    getString(extraMeta, "trace.id"));
            ctxMissing = (containsIgnoreCase(rid, "rid-missing-") || containsIgnoreCase(sid, "sid-missing-"));
        }
        if (ctxMissing) {
            String label = (ctxMissingCount != null && ctxMissingCount > 0)
                    ? ("CtxMissing:" + ctxMissingCount)
                    : "CtxMissing";
            pills.add(pill(label, "warn"));
        }

        // Web await-timeout local cooldown (fail-soft backoff) visibility
        boolean naverAwaitCooldown = truthy(extraMeta.get("web.failsoft.rateLimitBackoff.naver.awaitTimeoutApplied"));
        boolean braveAwaitCooldown = truthy(extraMeta.get("web.failsoft.rateLimitBackoff.brave.awaitTimeoutApplied"));
        if (naverAwaitCooldown || braveAwaitCooldown) {
            String who = (naverAwaitCooldown ? "N" : "") + (braveAwaitCooldown ? "B" : "");
            pills.add(pill("AwaitCooldown:" + who, "warn"));
        }

        // Await-timeout counts (operator quick glance)
        Long nAwaitTimeout = toLong(extraMeta.get("web.await.events.summary.engine.Naver.cause.await_timeout.count"));
        Long bAwaitTimeout = toLong(extraMeta.get("web.await.events.summary.engine.Brave.cause.await_timeout.count"));
        if ((nAwaitTimeout != null && nAwaitTimeout > 0) || (bAwaitTimeout != null && bAwaitTimeout > 0)) {
            long n = (nAwaitTimeout == null ? 0L : nAwaitTimeout.longValue());
            long b = (bAwaitTimeout == null ? 0L : bAwaitTimeout.longValue());
            pills.add(pill("AwaitTimeout:N" + n + " B" + b, "warn"));
        }

        // QueryTransformer softCooldown remaining time (operator quick glance)
        Long qtxRemainingMs = toLong(extraMeta.get("qtx.softCooldown.remainingMs"));
        if (qtxRemainingMs != null && qtxRemainingMs > 0) {
            pills.add(pill("QtxCooldown:" + qtxRemainingMs + "ms", "warn"));
        }

        // KeywordSelection fallback seed specificity score (debug quick glance)
        Long baseScore = toLong(extraMeta.get("keywordSelection.fallback.seed.baseScore"));
        Long uqScore = toLong(extraMeta.get("keywordSelection.fallback.seed.uqScore"));
        if ((baseScore != null && baseScore > 0) || (uqScore != null && uqScore > 0)) {
            long bs = (baseScore == null ? 0L : baseScore.longValue());
            long us = (uqScore == null ? 0L : uqScore.longValue());
            pills.add(pill("SeedScore:" + bs + "→" + us, "info"));
        }

        if (pills.isEmpty())
            return "";
        return "<span class='trace-pills'>" + String.join("", pills) + "</span>";
    }

    private String pill(String text, String kind) {
        String cls = "trace-pill" + (kind == null || kind.isBlank() ? "" : (" " + kind));
        return "<span class='" + cls + "'>" + escape(text) + "</span>";
    }

    private String renderAblationPanel(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty())
            return "";
        boolean has = extraMeta.containsKey("ablation.score")
                || extraMeta.containsKey("ablation.probabilities")
                || extraMeta.containsKey("ablation.top")
                || extraMeta.containsKey("ablation.byGuard")
                || extraMeta.containsKey("ablation.byStep");
        if (!has)
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append("<h3>D) Ablation (why degraded?)</h3>");

        sb.append("<div class='trace-callout'>");
        sb.append("<b>score</b>: ").append(escape(String.valueOf(extraMeta.getOrDefault("ablation.score", ""))));
        sb.append(" · <b>events</b>: ")
                .append(escape(String.valueOf(extraMeta.getOrDefault("ablation.events.count", ""))));
        sb.append(" · <b>finalized</b>: ").append(escape(String.valueOf(
                extraMeta.getOrDefault("ablation.finalized", extraMeta.getOrDefault("uaw.ablation.finalized", "")))));
        sb.append("</div>");

        // Summary (top guard/step)
        Object summaryObj = extraMeta.get("ablation.summary");
        if (summaryObj instanceof Map<?, ?> summary) {
            sb.append("<div class='trace-callout small'>");
            Object topGuard = summary.get("topGuard");
            Object topStep = summary.get("topStep");
            if (topGuard != null)
                sb.append("<b>topGuard</b>: ").append(escape(String.valueOf(topGuard))).append(" ");
            if (topStep != null)
                sb.append("<b>topStep</b>: ").append(escape(String.valueOf(topStep)));
            sb.append("</div>");
        }

        appendMiniTable(sb, "By Guard", extraMeta.get("ablation.byGuard"), List.of("guard", "p", "expectedDelta"));
        appendMiniTable(sb, "By Step", extraMeta.get("ablation.byStep"), List.of("step", "p", "expectedDelta"));
        appendMiniTable(sb, "Top Contributors", extraMeta.get("ablation.top"),
                List.of("p", "delta", "guard", "step", "note", "eventId"));
        appendMiniTable(sb, "All Probabilities", extraMeta.get("ablation.probabilities"),
                List.of("p", "delta", "guard", "step", "note", "eventId"));

        sb.append("</div>");
        return sb.toString();
    }

    private void appendMiniTable(StringBuilder sb, String title, Object rowsObj, List<String> cols) {
        if (!(rowsObj instanceof List<?> rows) || rows.isEmpty())
            return;

        sb.append("<details class='trace-details' open>");
        sb.append("<summary>").append(escape(title)).append("</summary>");

        sb.append("<table class='trace-table small'>");
        sb.append("<thead><tr>");
        for (String c : cols) {
            sb.append("<th>").append(escape(c)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");

        int limit = 50;
        int i = 0;
        for (Object o : rows) {
            if (i++ >= limit)
                break;
            if (!(o instanceof Map<?, ?> m))
                continue;
            sb.append("<tr>");
            for (String c : cols) {
                Object v = m.get(c);
                sb.append("<td>").append(escape(truncate(String.valueOf(v), 200))).append("</td>");
            }
            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        if (rows.size() > limit) {
            sb.append("<div class='muted small'>showing ").append(limit).append(" of ").append(rows.size())
                    .append("</div>");
        }
        sb.append("</details>");
    }

    private String renderDebugCopilotPanel(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty())
            return "";
        boolean has = extraMeta.containsKey("dbg.copilot.summary")
                || extraMeta.containsKey("dbg.copilot.actions")
                || extraMeta.containsKey("dbg.copilot.causes");
        if (!has)
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append("<h3>E) Debug Copilot</h3>");

        Object ok = extraMeta.get("dbg.copilot.ok");
        Object summary = extraMeta.get("dbg.copilot.summary");
        Object actions = extraMeta.get("dbg.copilot.actions");
        Object causes = extraMeta.get("dbg.copilot.causes");
        Object traceId = extraMeta.get("dbg.copilot.traceId");
        Object sid = extraMeta.get("dbg.copilot.sid");

        sb.append("<div class='trace-callout'>");
        sb.append("<b>ok</b>: ").append(escape(String.valueOf(ok)));
        if (traceId != null)
            sb.append(" · <b>traceId</b>: ").append(escape(String.valueOf(traceId)));
        if (sid != null)
            sb.append(" · <b>sid</b>: ").append(escape(String.valueOf(sid)));
        if (summary != null)
            sb.append(" · ").append(escape(truncate(String.valueOf(summary), 600)));
        sb.append("</div>");

        // Ranked causes (if present)
        appendMiniTable(sb, "Top Causes (ranked)", causes, List.of("rank", "score", "title", "evidence"));

        if (actions instanceof List<?> list && !list.isEmpty()) {
            sb.append("<details class='trace-details' open>");
            sb.append("<summary>Commands / actions</summary>");
            sb.append("<ul class='trace-actions'>");
            int limit = 30;
            int i = 0;
            for (Object a : list) {
                if (i++ >= limit)
                    break;
                String v = truncate(String.valueOf(a), 900);
                if (v.startsWith("#")) {
                    sb.append("<li><b>").append(escape(v)).append("</b></li>");
                } else if (v.startsWith("rg ") || v.startsWith("export ")) {
                    sb.append("<li><code>").append(escape(v)).append("</code></li>");
                } else {
                    sb.append("<li>").append(escape(v)).append("</li>");
                }
            }
            sb.append("</ul>");
            sb.append("</details>");
        } else if (actions != null) {
            sb.append("<div class='trace-callout small'>").append(escape(String.valueOf(actions))).append("</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

}
