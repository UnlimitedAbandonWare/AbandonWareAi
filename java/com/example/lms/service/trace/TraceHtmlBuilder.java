package com.example.lms.service.trace;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collections;
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
                .append(" ").append(riskBadgeHtml(risk));
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
        sb.append("<table class='trace-kv'>");
        java.util.Set<String> shown = new java.util.HashSet<>();

        // Make "why STRIKE/BYPASS" visible without digging into scattered fields.
        appendKvGroup(sb, extraMeta, shown, "Mode",
                java.util.List.of("orch.mode", "orch.strike", "orch.compression", "orch.bypass", "orch.reason",
                        "orch.webRateLimited", "orch.auxLlmDown", "orch.highRisk", "orch.irregularity",
                        "orch.userFrustration", "bypassReason"));

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
                        "aux.queryTransformer",
                        "aux.queryTransformer.blocked",
                        "aux.queryTransformer.blocked.reason",
                        "aux.disambiguation",
                        "aux.disambiguation.blocked",
                        "aux.disambiguation.blocked.reason",

                        "nightmare.breaker.openAtMs",
                        "nightmare.breaker.openUntilMs",
                        "nightmare.breaker.openUntilMs.last",
                        "nightmare.mode"
                ));
        appendKvGroup(sb, extraMeta, shown, "Guard",
                java.util.List.of("guard.final.action", "guard.final.coverageScore", "guard.inconsistentTemplate",
                        "guard.minCitations.required", "guard.minCitations.actual"));
        appendMinCitationsExplainRow(sb, extraMeta, shown);

        appendKvPrefixGroup(sb, extraMeta, shown, "Plan", "plan.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "QueryPlanner", "queryPlanner.", 24);

        // Custom (human-friendly) debug UX for web.* fields
        appendWebSelectedTerms(sb, extraMeta, shown);
        appendWebAwaitEvents(sb, extraMeta, shown);
        appendWebFailSoftRuns(sb, extraMeta, shown);

        appendKvPrefixGroup(sb, extraMeta, shown, "Web", "web.", 24);
        sb.append("</table></details></div>");
        return sb.toString();
    }

    private static String buildOrchestrationSummary(Map<String, Object> extraMeta, List<Content> webTopK,
            List<Content> vectorTopK) {
        java.util.List<String> parts = new java.util.ArrayList<>();

        // Bubble up STRIKE/BYPASS/... to the collapsed summary so it's visible without scrolling.
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
        sb.append("<div class='trace-orch-callout' style='margin:10px 0;padding:10px;border:1px solid #ddd;border-left:6px solid ")
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

        long timeouts = 0;
        long nonOk = 0;
        Long maxWaited = null;
        for (java.util.Map<String, Object> ev : events) {
            String cause = String.valueOf(ev.getOrDefault("cause", "")).toLowerCase();
            boolean skip = cause.equals("missing_future") || cause.startsWith("skip_");
            boolean okish = cause.equals("ok") || cause.equals("done") || cause.equals("done_null") || skip;
            boolean nonOkFlag = truthy(ev.get("nonOk")) || !okish;
            boolean timeoutFlag = cause.equals("timeout") || cause.equals("budget_exhausted")
                    || cause.equals("timeout_soft") || cause.equals("timeout_hard");
            if (timeoutFlag)
                timeouts++;
            if (nonOkFlag)
                nonOk++;
            Long w = toLong(ev.get("waitedMs"));
            if (w != null && (maxWaited == null || w > maxWaited)) {
                maxWaited = w;
            }
        }

        String timeoutPct = total > 0 ? String.valueOf(Math.round((timeouts * 100.0) / total)) : "0";
        String nonOkPct = total > 0 ? String.valueOf(Math.round((nonOk * 100.0) / total)) : "0";

        boolean open = (timeouts > 0) || (nonOk > 0);

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
        sb.append(" timeout=").append(timeouts).append(" (").append(timeoutPct).append("%)");
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

            long engTimeouts = 0;
            long engNonOk = 0;
            for (java.util.Map<String, Object> ev : evs) {
                String cause = String.valueOf(ev.getOrDefault("cause", "")).toLowerCase();
                boolean skip = cause.equals("missing_future") || cause.startsWith("skip_");
            boolean okish = cause.equals("ok") || cause.equals("done") || cause.equals("done_null") || skip;
                boolean nonOkFlag = truthy(ev.get("nonOk")) || !okish;
                boolean timeoutFlag = cause.equals("timeout") || cause.equals("budget_exhausted")
                        || cause.equals("timeout_soft") || cause.equals("timeout_hard");
                if (timeoutFlag)
                    engTimeouts++;
                if (nonOkFlag)
                    engNonOk++;
            }

            boolean engOpen = engTimeouts > 0 || engNonOk > 0;

            String engTimeoutPct = evs.size() > 0
                    ? String.valueOf(Math.round((engTimeouts * 100.0) / evs.size()))
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
                    .append(", timeout=").append(engTimeouts).append(" (").append(engTimeoutPct).append("%)")
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
                boolean okish = causeLower.equals("ok") || causeLower.equals("done") || causeLower.equals("done_null") || skip;
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
     * <p>This helps when a single request triggers multiple searches (canonical + extraSearchCalls),
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

        boolean anyOutZero = false;
        boolean anyFallback = false;
        for (java.util.Map<String, Object> r : runs) {
            Long out = toLong(r.get("outCount"));
            if (out != null && out == 0) {
                anyOutZero = true;
            }
            Object fb = r.get("starvationFallback");
            if (fb != null && !String.valueOf(fb).isBlank()) {
                anyFallback = true;
            }
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web FailSoft Runs</th></tr>");
        sb.append("<tr><th>web.failsoft.runs</th><td>");

        sb.append("<details class='trace-failsoft-runs'");
        if (anyOutZero || anyFallback || runs.size() > 1) {
            sb.append(" open");
        }
        sb.append(">");

        sb.append("<summary>");
        sb.append("runs=").append(runs.size());
        if (anyFallback) {
            sb.append(" fallback=1");
        }
        if (anyOutZero) {
            sb.append(" outZero=1");
        }
        sb.append("</summary>");

        sb.append("<table class='trace-await-table'>");
        sb.append("<thead><tr>");
        sb.append("<th>#</th><th>runId</th><th>executedQuery</th><th>outCount</th><th>officialOnly</th><th>minCitations</th><th>clamped</th><th>fallback</th><th>stageCountsSelected</th><th>opts</th><th>candidates</th>");
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

            String opts = "incDevCommunity=" + incDev + ", fbEnabled=" + fbEnabled + ", trigger=" + fbTrigger
                    + ", max=" + fbMax + ", intentAllowed=" + fbIntentAllowed;

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

    // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_RENDER
    private static void appendOrchPartsTable(StringBuilder sb, java.util.Map<String, Object> extraMeta, java.util.Set<String> shown) {
        if (sb == null || extraMeta == null || extraMeta.isEmpty()) return;

        Object summaryObj = extraMeta.get("orch.parts.summary");
        Object tableObj = extraMeta.get("orch.parts.table");
        if (summaryObj == null && tableObj == null) return;

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
                if (row == null) continue;
                sb.append("<div><code>")
                        .append(escape(String.valueOf(row)))
                        .append("</code></div>");
            }
            sb.append("</details>");
            sb.append("</td></tr>");
        }
    }

}