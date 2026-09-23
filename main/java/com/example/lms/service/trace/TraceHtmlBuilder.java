package com.example.lms.service.trace;

import com.example.lms.trace.attribution.TraceAblationAttributionResult;
import com.example.lms.trace.attribution.TraceAblationAttributionService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Search Trace UI panel HTML builder.
 *
 * Requirements:
 * - (A) Show redacted snippet diagnostics (hash/count/len/host).
 * - (B) Show final context (rerank TopK + vector/RAG TopK) in a separate
 * section.
 *
 * Notes:
 * - Raw snippets may contain HTML from search engines, so never render them directly.
 * - Final context uses Content.textSegment() to extract text and metadata
 * safely.
 */
@Component
public class TraceHtmlBuilder {
    private static final System.Logger LOG = System.getLogger(TraceHtmlBuilder.class.getName());

    private final TraceAblationAttributionService traceAblationAttributionService;

    public TraceHtmlBuilder(TraceAblationAttributionService traceAblationAttributionService) {
        this.traceAblationAttributionService = traceAblationAttributionService;
    }

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
        Map<String, Object> safeExtraMeta = sanitizeMeta(extraMeta);

        RiskLevel risk = evaluateRisk(safeExtraMeta);
        String riskClass = cssRiskClass(risk);
        boolean autoOpen = risk != RiskLevel.OK;

        String summaryLine = buildSummaryLine(rawTrace, rawCount, webTopK, vectorTopK, safeExtraMeta, risk);

        StringBuilder sb = new StringBuilder();
        sb.append("<details data-trace-redacted=\"1\" class=\"search-trace ").append(riskClass).append("\"");
        if (autoOpen) {
            sb.append(" open");
        }
        sb.append(">");
        sb.append("<summary>");
        sb.append(summaryLine);
        sb.append("</summary>");
        sb.append("<div class='trace-body'>");

        sb.append(renderRawSearchPanel(rawTrace, rawSnippets, safeExtraMeta));
        sb.append(renderTopKPanel("B) Final Context (LLM Input)",
                webEnabled ? webTopK : null,
                vectorEnabled ? vectorTopK : null));
        sb.append(TraceHtmlCitableEvidenceRenderer.render(safeExtraMeta));
        sb.append(renderOrchestrationPanel(safeExtraMeta, webTopK, vectorTopK, risk));

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
        RiskLevel risk = evaluateRisk(extraMeta = new java.util.LinkedHashMap<>(sanitizeMeta(extraMeta)));
        String riskClass = cssRiskClass(risk);

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html><html data-trace-redacted=\"1\"><head><meta charset=\"utf-8\"/>");
        sb.append("<title>Trace Snapshot ").append(escape(snapshotId)).append("</title>");
        // Minimal inline CSS so the page is readable standalone.
        sb.append(TraceHtmlLayout.snapshotStyle());
        sb.append("</head><body>");

        sb.append("<details class=\"search-trace ").append(riskClass).append("\" open>");
        sb.append("<summary>");
        sb.append("<span class=\"trace-mono\">").append(escape(SafeRedactor.traceLabelOrFallback(reason, ""))).append("</span>");
        if (status != null) {
            sb.append(" <span class=\"text-muted small\">status=").append(escape(String.valueOf(status)))
                    .append("</span>");
        }
        if (path != null && !path.isBlank()) {
            sb.append(" <span class=\"text-muted small\">\u00b7 ").append(escape(safeDiagnostic("rawQuery", path))).append("</span>");
        }
        sb.append("</summary>");

        sb.append("<div class='trace-body'>");

        sb.append("<div class='trace-section'>");
        sb.append(TraceHtmlLayout.renderPanelHeader("Snapshot Meta", "Identifiers & capture context"));
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
        sb.append(TraceHtmlLayout.renderPanelHeader("A) Raw Snippets",
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
                    .append(" 쨌 total=").append(escape(fmtMs(rawTrace.totalMs))).append("</span>");
            sb.append("</summary>");

            sb.append("<div class='trace-kv'>");
            sb.append("<div><code>query</code>: <span class='trace-mono'>").append(escape(safeDiagnostic("query", rawTrace.query)))
                    .append("</span></div>");
            sb.append("<div><code>domainFilterEnabled</code>: <b>").append(rawTrace.domainFilterEnabled).append("</b>");
            if (!rawTrace.domainFilterEnabled && rawTrace.reasonDomainFilterDisabled != null
                    && !rawTrace.reasonDomainFilterDisabled.isBlank()) {
                sb.append(" <span class='text-muted small'>(").append(escape(SafeRedactor.traceLabelOrFallback(rawTrace.reasonDomainFilterDisabled, "reason")))
                        .append(")</span>");
            }
            sb.append("</div>");
            sb.append("<div><code>keywordFilterEnabled</code>: <b>").append(rawTrace.keywordFilterEnabled)
                    .append("</b>");
            if (!rawTrace.keywordFilterEnabled && rawTrace.reasonKeywordFilterDisabled != null
                    && !rawTrace.reasonKeywordFilterDisabled.isBlank()) {
                sb.append(" <span class='text-muted small'>(").append(escape(SafeRedactor.traceLabelOrFallback(rawTrace.reasonKeywordFilterDisabled, "reason")))
                        .append(")</span>");
            }
            sb.append("</div>");
            if (rawTrace.suffixApplied != null && !rawTrace.suffixApplied.isBlank()) {
                sb.append("<div><code>suffixApplied</code>: <span class='trace-mono'>")
                        .append(escape(safeDiagnostic("search.suffixApplied", rawTrace.suffixApplied))).append("</span></div>");
            }
            if (rawTrace.orgResolved) {
                sb.append("<div><code>orgResolved</code>: <span class='trace-mono'>true</span></div>");
            }
            if (rawTrace.orgCanonical != null && !rawTrace.orgCanonical.isBlank()) {
                sb.append("<div><code>orgCanonical</code>: <span class='trace-mono'>")
                        .append(escape(safeDiagnostic("search.orgCanonical", rawTrace.orgCanonical))).append("</span></div>");
            }
            if (boostActive && rawTrace.siteFiltersApplied != null && !rawTrace.siteFiltersApplied.isEmpty()) {
                sb.append("<div><code>siteFiltersApplied</code>: <span class='trace-mono'>");
                int lim = Math.min(rawTrace.siteFiltersApplied.size(), 8);
                for (int i = 0; i < lim; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(escape(safeDiagnostic("search.siteFiltersApplied", rawTrace.siteFiltersApplied.get(i))));
                }
                if (rawTrace.siteFiltersApplied.size() > lim)
                    sb.append(", ...");
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
                sb.append("<label><input type='checkbox' data-steps-filter='slow'> slow(??000ms)</label> ");
                sb.append(
                        "<input type='text' class='trace-input small' placeholder='filter query?? data-steps-filter='q'>");
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
                                .append("' class='text-muted'>??truncated)??/td></tr>");
                        break;
                    }
                    if (st == null)
                        continue;
                    String qfull = safeValueOrDefault(st.query, "");
                    int qlen = qfull.length();

                    String qtxt = safeDiagnostic("query", qfull);
                    if (qtxt.length() > maxQuery)
                        qtxt = qtxt.substring(0, maxQuery) + "...";
                    String qData = String.valueOf(SafeRedactor.hash12(qfull));
                    if (qData.length() > 400)
                        qData = qData.substring(0, 400) + "...";

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
                sb.append("<li><code>").append(escape(safeDiagnostic("snippet", snippet))).append("</code></li>");
            }
            sb.append("</ol>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String renderTopKPanel(String title, List<Content> webTopK, List<Content> vectorTopK) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append(TraceHtmlLayout.renderPanelHeader(title, "Final output after Rerank/Filtering"));

        if (webTopK != null && !webTopK.isEmpty()) {
            sb.append("<div class='trace-sub-title'>Web TopK (" + webTopK.size() + ")</div>");
            sb.append(TraceHtmlContentListRenderer.render(webTopK, "W", 10));
        } else if (webTopK == null) {
            sb.append("<div class='text-muted small'>Web: disabled</div>");
        } else {
            sb.append("<div class='text-muted small'>Web: 0 items</div>");
        }

        if (vectorTopK != null && !vectorTopK.isEmpty()) {
            sb.append("<div class='trace-sub-title'>Vector TopK (" + vectorTopK.size() + ")</div>");
            sb.append(TraceHtmlContentListRenderer.render(vectorTopK, "V", 10));
        } else if (vectorTopK == null) {
            sb.append("<div class='text-muted small'>Vector: disabled</div>");
        } else {
            sb.append("<div class='text-muted small'>Vector: 0 items</div>");
        }

        sb.append("</div>");
        return sb.toString();
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
        String lk = k.toLowerCase(java.util.Locale.ROOT);
        return "<div><code>" + escape(k) + "</code>: <span class='trace-mono'>" + escape(("id".equals(lk) || "ts".equals(lk) || "method".equals(lk) || "status".equals(lk)) ? safeValue(v) : (("reason".equals(lk) || "error".equals(lk)) ? SafeRedactor.traceLabelOrFallback(v, "") : ("path".equals(lk) ? safeDiagnostic("rawQuery", v) : safeDiagnostic(k, v)))) + "</span></div>";
    }

    private static String fmtMs(long ms) {
        if (ms < 1000)
            return ms + "ms";
        else
            return String.format("%.2fs", ms / 1000.0);
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
                || truthy(extraMeta.get("web.brave.429")) || truthy(extraMeta.get("web.serpapi.429"))
                || truthy(extraMeta.get("web.tavily.429")) || truthy(extraMeta.get("web.naver.skippedByBreaker"))
                || truthy(extraMeta.get("web.brave.skippedByBreaker"))
                || truthy(extraMeta.get("web.serpapi.skippedByBreaker"))
                || truthy(extraMeta.get("web.tavily.skippedByBreaker")))
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
        sb.append("Search Trace - query: ").append(escape(safeDiagnostic("query", query)))
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
        sb.append(TraceHtmlLayout.renderPanelHeader("C) Orchestration State (aux / guard)", "Problem tracking and diagnostics"));
        boolean forceOpen = (risk != null && risk != RiskLevel.OK)
                || truthy(extraMeta.get("orch.strike"))
                || truthy(extraMeta.get("orch.bypass"))
                || truthy(extraMeta.get("orch.compression"))
                || truthy(extraMeta.get("orch.webRateLimited"))
                || truthy(extraMeta.get("orch.auxLlmDown"));
        sb.append("<details class='trace-orch'").append(forceOpen ? " open" : "")
                .append(">");
        sb.append("<summary>").append(escape(summary)).append("</summary>");
        sb.append(TraceHtmlOrchestrationModeCalloutRenderer.render(extraMeta));
        sb.append(TraceHtmlWebFailSoftRiskCalloutRenderer.render(extraMeta));
        sb.append(TraceHtmlSoakWebKpiCopyCalloutRenderer.render(extraMeta));
        sb.append(renderTraceAblationAttributionCallout(extraMeta, webTopK, vectorTopK));
        sb.append(TraceHtmlRelationThumbnailCallouts.renderBudget(extraMeta));
        sb.append(TraceHtmlRelationThumbnailCallouts.renderContextLayers(extraMeta));
        sb.append(TraceHtmlRelationThumbnailCallouts.renderSliceMap(extraMeta));
        sb.append("<table class='trace-kv'>");
        java.util.Set<String> shown = new java.util.HashSet<>();

        // Make "why STRIKE/BYPASS" visible without digging into scattered fields.
        appendKvGroup(sb, extraMeta, shown, "Mode",
                java.util.List.of("orch.mode", "orch.strike", "orch.compression", "orch.bypass", "orch.reason",
                        "orch.webRateLimited", "orch.auxLlmDown", "orch.highRisk", "orch.irregularity",
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

        if (extraMeta.containsKey("orch.events.v1")) {
            shown.add("orch.events.v1"); // handled by appendRagPipelineEvents()
        }
        appendRagPipelineEvents(sb, extraMeta, shown);

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
        appendKvPrefixGroup(sb, extraMeta, shown, "Stage Boundary", "stageBoundary.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "MLA Breadcrumb Step", "mla.breadcrumb.step.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "ML", "ml.", 24);
        appendKvPrefixGroup(sb, extraMeta, shown, "Embedding", "embed.", 24);

        // Custom (human-friendly) debug UX for web.* fields
        TraceHtmlWebSelectedTermsRenderer.append(sb, extraMeta, shown);
        appendWebNaverPlanHintBoostOnlyOverlay(sb, extraMeta, shown);
        TraceHtmlWebAwaitEventsRenderer.append(sb, extraMeta, shown);
        TraceHtmlWebFailSoftRunsRenderer.append(sb, extraMeta, shown);

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
            return TraceHtmlAblationAttributionCalloutRenderer.render(res);
        } catch (Exception e) {
            String errorType = errorType(e);
            TraceStore.put("traceHtml.ablation.suppressed.render", true);
            TraceStore.put("traceHtml.ablation.suppressed.render.errorType", errorType);
            LOG.log(System.Logger.Level.DEBUG, "Trace ablation callout suppressed errorType={0}",
                    errorType);
            return "";
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
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
        String skipReason = SafeRedactor.traceLabelOrFallback(extraMeta.get("web.naver.planHintBoostOnly.skipped.reason"), "");
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
            sb.append(" 쨌 reason=").append(escape(skipReason));
        }
        Object cnt = extraMeta.get("web.naver.planHintBoostOnly.count");
        if (cnt != null)
            sb.append(" 쨌 count=").append(escape(String.valueOf(cnt)));
        Object sc = extraMeta.get("web.naver.planHintBoostOnly.skipped.count");
        if (sc != null)
            sb.append(" 쨌 skipped=").append(escape(String.valueOf(sc)));
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
            String vv = k.toLowerCase(java.util.Locale.ROOT).contains("reason") ? SafeRedactor.traceLabelOrFallback(v, "reason") : safeValueOrDefault(v, "");
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


    private static Long toLong(Object v) {
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric))
                return n.longValue();
            LOG.log(System.Logger.Level.DEBUG, "Trace HTML numeric fallback targetType={0} valueLength={1} errorType={2}",
                    "long", String.valueOf(v).length(), "invalid_number");
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.DEBUG, "Trace HTML numeric fallback targetType={0} valueLength={1} errorType={2}",
                    "long", v == null ? 0 : String.valueOf(v).length(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static void appendKvRow(StringBuilder sb, Map<String, Object> meta, java.util.Set<String> shown,
            String key) {
        Object v = meta.get(key);
        if (v == null)
            return;
        sb.append("<tr><th>").append(escape(key)).append("</th><td><code>").append(escape(safeValue(key.toLowerCase(java.util.Locale.ROOT).contains("reason") ? SafeRedactor.traceLabelOrFallback(v, "reason") : v)))
                .append("</code></td></tr>");
        shown.add(key);
    }

    private static String safeValue(Object v) {
        if (v == null)
            return "null";
        String s = String.valueOf(v).replace("\n", " ").trim();
        return s.length() > 800 ? s.substring(0, 800) + "..." : s;
    }

    private static String safeDiagnostic(String key, Object v) {
        Object safe = SafeRedactor.diagnosticValue(key, v, 800);
        return safeValue(safe);
    }

    private static Map<String, Object> sanitizeMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return java.util.Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            if (e == null || e.getKey() == null) continue;
            String key = e.getKey();
            String displayKey = safeMetaKey(key);
            if ("rag.evidence.public".equals(key)) {
                out.put(displayKey, sanitizePublicEvidence(e.getValue()));
            } else {
                out.put(displayKey, SafeRedactor.diagnosticValue(key.toLowerCase(java.util.Locale.ROOT).contains("soakkpijson") ? "query" : key, e.getValue(), 800));
            }
            if (++count >= 800) {
                out.put("_truncated", true);
                break;
            }
        }
        return out;
    }

    private static String safeMetaKey(String key) {
        if (key == null || key.isBlank()) {
            return "field";
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        boolean tokenBucket = normalized.equals("tokenbucket")
                || normalized.endsWith(".tokenbucket")
                || normalized.equals("querytokenbucket")
                || normalized.endsWith(".querytokenbucket");
        boolean sensitive = normalized.contains("authorization")
                || normalized.contains("apikey")
                || (normalized.contains("servicerole") && normalized.contains("key"))
                || normalized.contains("secret")
                || (!tokenBucket && normalized.contains("token"))
                || normalized.contains("password")
                || normalized.contains("cookie")
                || normalized.contains("ownertoken")
                || (normalized.contains("openai") && normalized.contains("key"));
        return sensitive ? SafeRedactor.hashValue(key) : SafeRedactor.traceLabelOrFallback(key, "field");
    }

    private static Object sanitizePublicEvidence(Object raw) {
        if (!(raw instanceof Iterable<?> items)) {
            return SafeRedactor.diagnosticValue("rag.evidence.public", raw, 800);
        }
        java.util.List<Object> out = new java.util.ArrayList<>();
        int count = 0;
        for (Object item : items) {
            if (++count > 40) {
                out.add("(truncated)");
                break;
            }
            if (!(item instanceof Map<?, ?> map)) {
                out.add(SafeRedactor.diagnosticValue("rag.evidence.public", item, 800));
                continue;
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            copyEvidenceField(row, map, "marker", 80);
            copyEvidenceField(row, map, "kind", 80);
            copyEvidenceField(row, map, "title", 300);
            copyEvidenceField(row, map, "source", 1000);
            copyEvidenceField(row, map, "filePath", 1000);
            copyEvidenceField(row, map, "lineStart", 40);
            copyEvidenceField(row, map, "lineEnd", 40);
            copyEvidenceField(row, map, "rank", 40);
            copyEvidenceField(row, map, "confidence", 80);
            copyEvidenceField(row, map, "confidenceSource", 120);
            out.add(row);
        }
        return out;
    }

    private static void copyEvidenceField(java.util.Map<String, Object> out, Map<?, ?> source, String key, int max) {
        Object value = source.get(key);
        if (value == null) return;
        if (value instanceof Number || value instanceof Boolean) { out.put(key, value); return; }
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) return;
        switch (key) {
            case "marker", "kind", "lineStart", "lineEnd", "rank", "confidence", "confidenceSource" ->
                    out.put(key, SafeRedactor.traceLabelOrFallback(text.length() <= max ? text : text.substring(0, max), ""));
            case "source" -> out.put(key, SafeRedactor.diagnosticValue("rag.evidence.public.url", text, max));
            case "filePath" -> out.put(key, "pathHash=" + SafeRedactor.hashValue(text) + " pathLength=" + text.length());
            case "title" -> out.put(key, SafeRedactor.diagnosticValue("rag.evidence.public.textPreview", text, max));
            default -> out.put(key, SafeRedactor.diagnosticValue("rag.evidence.public.rawText", text, max));
        }
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
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric))
                return n.intValue();
            LOG.log(System.Logger.Level.DEBUG, "Trace HTML numeric fallback targetType={0} valueLength={1} errorType={2}",
                    "int", String.valueOf(v).length(), "invalid_number");
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.DEBUG, "Trace HTML numeric fallback targetType={0} valueLength={1} errorType={2}",
                    "int", v == null ? 0 : String.valueOf(v).length(), e.getClass().getSimpleName());
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
            sb.append(" 쨌 missing=").append(missingCount);
        if (generatedCount > 0)
            sb.append(" 쨌 generated=").append(generatedCount);
        if (mdcBridgeCount > 0)
            sb.append(" 쨌 mdcBridge=").append(mdcBridgeCount);
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
                    detail = SafeRedactor.traceLabelOrFallback(reason, "reason");
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

    private static void appendRagPipelineEvents(StringBuilder sb, java.util.Map<String, Object> extraMeta,
            java.util.Set<String> shown) {
        if (sb == null || extraMeta == null || extraMeta.isEmpty()) {
            return;
        }
        Object evObj = extraMeta.get("orch.events.v1");
        if (!(evObj instanceof Iterable<?> it)) {
            return;
        }

        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        for (Object o : it) {
            java.util.Map<String, Object> row = eventMap(o);
            if (!row.isEmpty()) {
                events.add(row);
            }
            if (events.size() >= 200) {
                break;
            }
        }
        if (events.isEmpty()) {
            return;
        }

        int controls = 0;
        int failures = 0;
        java.util.Map<String, Integer> byStage = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> ev : events) {
            java.util.Map<String, Object> failure = eventMap(ev.get("failure"));
            java.util.Map<String, Object> control = eventMap(ev.get("control"));
            String stage = safeValueOrDefault(ev.get("stage"), "unknown");
            byStage.put(stage, byStage.getOrDefault(stage, 0) + 1);
            if (isNonBlank(safeValueOrDefault(failure.get("reasonCode"), ""))) {
                failures++;
            }
            if (isNonBlank(safeValueOrDefault(control.get("action"), ""))) {
                controls++;
            }
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>RAG Pipeline Events</th></tr>");
        sb.append("<tr><th>orch.events.v1</th><td>");
        sb.append("<details").append((failures > 0 || controls > 0) ? " open" : "").append(">");
        sb.append("<summary>events=").append(events.size())
                .append(" controls=").append(controls)
                .append(" failures=").append(failures);
        int shownStages = 0;
        for (java.util.Map.Entry<String, Integer> en : byStage.entrySet()) {
            if (shownStages++ >= 5) {
                sb.append(" ...");
                break;
            }
            sb.append(" / ").append(escape(en.getKey())).append("=").append(en.getValue());
        }
        sb.append("</summary>");

        sb.append("<table class='trace-table small'>");
        sb.append("<thead><tr>")
                .append("<th>#</th><th>phase</th><th>stage</th><th>step</th><th>status</th><th>counts</th><th>failure</th><th>control</th>")
                .append("</tr></thead><tbody>");

        int idx = 0;
        for (java.util.Map<String, Object> ev : events) {
            idx++;
            java.util.Map<String, Object> output = eventMap(ev.get("output"));
            java.util.Map<String, Object> failure = eventMap(ev.get("failure"));
            java.util.Map<String, Object> control = eventMap(ev.get("control"));

            Object seqObj = ev.get("seq");
            String seq = (seqObj != null && !String.valueOf(seqObj).equals("null")) ? String.valueOf(seqObj)
                    : String.valueOf(idx);
            String phase = safeValueOrDefault(ev.get("phase"), "");
            String stage = safeValueOrDefault(ev.get("stage"), "");
            String step = safeValueOrDefault(ev.get("step"), "");
            String status = safeValueOrDefault(ev.get("status"), "");
            String reason = firstNonBlank(
                    safeValueOrDefault(failure.get("reasonCode"), null),
                    safeValueOrDefault(control.get("reasonCode"), null),
                    "");
            String action = safeValueOrDefault(control.get("action"), "");
            String applied = safeValueOrDefault(control.get("applied"), "");

            String counts = "returned=" + safeValueOrDefault(output.get("returnedCount"), "-")
                    + " afterFilter=" + safeValueOrDefault(output.get("afterFilterCount"), "-")
                    + " selected=" + safeValueOrDefault(output.get("selectedCount"), "-")
                    + " ms=" + safeValueOrDefault(output.get("stageMs"), "-");
            String controlText = isNonBlank(action)
                    ? action + (isNonBlank(applied) ? " applied=" + applied : "")
                    : "";

            String rowId = "rag-pipeline-ev-" + escapeAttr(seq);
            sb.append("<tr id='").append(rowId).append("'>");
            sb.append("<td><a href='#").append(rowId).append("'><code>").append(escape(seq))
                    .append("</code></a></td>");
            sb.append("<td><code>").append(escape(phase)).append("</code></td>");
            sb.append("<td><code>").append(escape(stage)).append("</code></td>");
            sb.append("<td><code>").append(escape(step)).append("</code></td>");
            sb.append("<td><code>").append(escape(status)).append("</code></td>");
            sb.append("<td><code>").append(escape(counts)).append("</code></td>");
            sb.append("<td><code>").append(escape(reason)).append("</code></td>");
            sb.append("<td>");
            sb.append("<details class='trace-fold'>");
            sb.append("<summary><code>").append(escape(isNonBlank(controlText) ? controlText : "payload"))
                    .append("</code></summary>");
            sb.append("<div class='trace-kv'>");
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (Object k : ev.keySet()) {
                if (k != null) {
                    keys.add(String.valueOf(k));
                }
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
        shown.add("orch.events.v1");
    }

    private static java.util.Map<String, Object> eventMap(Object raw) {
        if (!(raw instanceof java.util.Map<?, ?> m)) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
            if (e == null || e.getKey() == null) {
                continue;
            }
            Object value = e.getValue() instanceof java.util.Map<?, ?> ? eventMap(e.getValue()) : e.getValue();
            out.put(String.valueOf(e.getKey()), String.valueOf(e.getKey()).toLowerCase(java.util.Locale.ROOT).contains("reason") ? SafeRedactor.traceLabelOrFallback(value, "reason") : value);
        }
        return out;
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
                sb.append(" 쨌 ").append(escape(en.getKey())).append("=").append(en.getValue());
            }
            if (byEvent.size() > 4) {
                sb.append(" 쨌 ...");
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

            String reason = SafeRedactor.traceLabelOrFallback(ev.get("reason"), "");

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
                Object display = "reason".equalsIgnoreCase(k) ? SafeRedactor.traceLabelOrFallback(v, "reason") : v;
                sb.append("<div><code>").append(escape(k)).append("</code>: <span class='trace-mono'>")
                        .append(escape(safeValue(display))).append("</span></div>");
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
        boolean serpapiAwaitCooldown = truthy(extraMeta.get("web.failsoft.rateLimitBackoff.serpapi.awaitTimeoutApplied"));
        boolean tavilyAwaitCooldown = truthy(extraMeta.get("web.failsoft.rateLimitBackoff.tavily.awaitTimeoutApplied"));
        if (naverAwaitCooldown || braveAwaitCooldown || serpapiAwaitCooldown || tavilyAwaitCooldown) {
            String who = (naverAwaitCooldown ? "N" : "") + (braveAwaitCooldown ? "B" : "")
                    + (serpapiAwaitCooldown ? "S" : "") + (tavilyAwaitCooldown ? "T" : "");
            pills.add(pill("AwaitCooldown:" + who, "warn"));
        }

        // Await-timeout counts (operator quick glance)
        Long nAwaitTimeout = toLong(extraMeta.get("web.await.events.summary.engine.Naver.cause.await_timeout.count"));
        Long bAwaitTimeout = toLong(extraMeta.get("web.await.events.summary.engine.Brave.cause.await_timeout.count"));
        Long sAwaitTimeout = toLong(extraMeta.get("web.await.events.summary.engine.SerpApi.cause.await_timeout.count"));
        Long tAwaitTimeout = toLong(extraMeta.get("web.await.events.summary.engine.Tavily.cause.await_timeout.count"));
        if ((nAwaitTimeout != null && nAwaitTimeout > 0)
                || (bAwaitTimeout != null && bAwaitTimeout > 0)
                || (sAwaitTimeout != null && sAwaitTimeout > 0)
                || (tAwaitTimeout != null && tAwaitTimeout > 0)) {
            long n = (nAwaitTimeout == null ? 0L : nAwaitTimeout.longValue());
            long b = (bAwaitTimeout == null ? 0L : bAwaitTimeout.longValue());
            long s = (sAwaitTimeout == null ? 0L : sAwaitTimeout.longValue());
            long t = (tAwaitTimeout == null ? 0L : tAwaitTimeout.longValue());
            pills.add(pill("AwaitTimeout:N" + n + " B" + b + " S" + s + " T" + t, "warn"));
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
            pills.add(pill("SeedScore:" + bs + "/" + us, "info"));
        }

        if (pills.isEmpty())
            return "";
        return "<span class='trace-pills'>" + String.join("", pills) + "</span>";
    }

    private String pill(String text, String kind) {
        String cls = "trace-pill" + (kind == null || kind.isBlank() ? "" : (" " + kind));
        return "<span class='" + cls + "'>" + escape(text) + "</span>";
    }

}
