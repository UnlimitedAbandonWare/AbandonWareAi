package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TraceHtmlWebAwaitEventsRenderer {

    private TraceHtmlWebAwaitEventsRenderer() {
    }

    static void append(StringBuilder sb, Map<String, Object> extraMeta, Set<String> shown) {
        Object eventsObj = extraMeta.get("web.await.events");
        if (!(eventsObj instanceof List<?> list)) {
            return;
        }

        List<Map<String, Object>> events = coerceEvents(list);
        if (events.isEmpty()) {
            return;
        }

        AwaitStats stats = AwaitStats.from(events);
        Map<String, List<Map<String, Object>>> byEngine = groupByEngine(events);
        String rootId = "trace-await-root-" + System.identityHashCode(extraMeta);

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web Await Events</th></tr>");
        sb.append("<tr><th>web.await.events</th><td>");
        sb.append("<details class='trace-await'");
        if (stats.open()) {
            sb.append(" open");
        }
        sb.append(">");

        appendSummary(sb, stats);
        sb.append("<div id='").append(rootId).append("'>");
        appendControls(sb, byEngine);
        appendEngineTables(sb, byEngine);
        appendScript(sb, rootId);
        sb.append("</div>");
        sb.append("</details>");
        sb.append("</td></tr>");

        shown.add("web.await.events");
        shown.add("web.await.last");
    }

    private static List<Map<String, Object>> coerceEvents(List<?> list) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (key != null) {
                        coerced.put(String.valueOf(key), entry.getValue());
                    }
                }
                events.add(coerced);
            }
        }
        return events;
    }

    private static Map<String, List<Map<String, Object>>> groupByEngine(List<Map<String, Object>> events) {
        Map<String, List<Map<String, Object>>> byEngine = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String engine = safeValue(event.get("engine"));
            if (engine == null || engine.isBlank() || "null".equals(engine)) {
                engine = "(unknown)";
            }
            byEngine.computeIfAbsent(engine, key -> new ArrayList<>()).add(event);
        }
        return byEngine;
    }

    private static void appendSummary(StringBuilder sb, AwaitStats stats) {
        sb.append("<summary>");
        sb.append("count=").append(stats.total());
        sb.append(" timeout=").append(stats.timeoutHard()).append(" (").append(stats.timeoutHardPct()).append("%)");
        sb.append(" timeoutSoft=").append(stats.timeoutSoft());
        sb.append(" timeoutAll=").append(stats.timeoutAll()).append(" (").append(stats.timeoutAllPct()).append("%)");
        sb.append(" nonOk=").append(stats.nonOk()).append(" (").append(stats.nonOkPct()).append("%)");
        sb.append(" soft=").append(stats.soft());
        sb.append(" hard=").append(stats.hard());
        if (stats.maxWaited() != null) {
            sb.append(" maxWaitedMs=").append(stats.maxWaited());
        }
        sb.append("</summary>");
    }

    private static void appendControls(StringBuilder sb, Map<String, List<Map<String, Object>>> byEngine) {
        sb.append("<div class='trace-await-controls'>");
        sb.append("<label><input type='checkbox' data-awf='timeoutOnly'> Timeout only</label>");
        sb.append("<label><input type='checkbox' data-awf='nonOkOnly'> non-ok only</label>");
        sb.append("<label><input type='checkbox' data-awf='softOnly'> Soft only</label>");
        sb.append("<label><input type='checkbox' data-awf='hardOnly'> Hard only</label>");

        sb.append("<label>Engine <select data-awf='engine'>");
        sb.append("<option value='*'>all</option>");
        for (String engine : byEngine.keySet()) {
            sb.append("<option value='").append(escapeAttr(engine)).append("'>").append(escape(engine)).append("</option>");
        }
        sb.append("</select></label>");

        sb.append("<label>Sort <select data-awf='sort'>");
        sb.append("<option value='none'>original</option>");
        sb.append("<option value='waitedDesc'>waitedMs desc</option>");
        sb.append("<option value='timeoutDesc'>timeoutMs desc</option>");
        sb.append("</select></label>");
        sb.append("</div>");
    }

    private static void appendEngineTables(StringBuilder sb, Map<String, List<Map<String, Object>>> byEngine) {
        int[] globalIdx = { 0 };
        for (Map.Entry<String, List<Map<String, Object>>> entry : byEngine.entrySet()) {
            String engine = entry.getKey();
            List<Map<String, Object>> events = entry.getValue();
            AwaitStats stats = AwaitStats.from(events);

            sb.append("<details class='trace-await-engine' data-engine='").append(escapeAttr(engine)).append("'");
            if (stats.open()) {
                sb.append(" open");
            }
            sb.append(">");
            sb.append("<summary>");
            sb.append(escape(engine));
            sb.append(" (count=").append(events.size())
                    .append(", timeout=").append(stats.timeoutHard()).append(" (").append(stats.timeoutHardPct()).append("%)")
                    .append(", timeoutSoft=").append(stats.timeoutSoft())
                    .append(", timeoutAll=").append(stats.timeoutAll()).append(" (").append(stats.timeoutAllPct()).append("%)")
                    .append(", nonOk=").append(stats.nonOk()).append(" (").append(stats.nonOkPct()).append("%)")
                    .append(")");
            sb.append("</summary>");

            sb.append("<table class='trace-await-table'>");
            sb.append("<thead><tr>");
            sb.append(
                    "<th>#</th><th>stage</th><th>engine</th><th>step</th><th>cause</th><th>timeoutMs</th><th>waitedMs</th><th>err</th><th>detail</th>");
            sb.append("</tr></thead>");
            sb.append("<tbody>");

            for (Map<String, Object> event : events) {
                globalIdx[0]++;
                appendEventRow(sb, globalIdx[0], engine, event);
            }

            sb.append("</tbody></table>");
            sb.append("</details>");
        }
    }

    private static void appendEventRow(StringBuilder sb, int globalIdx, String engine, Map<String, Object> event) {
        String stage = safeValue(event.get("stage"));
        String step = safeValue(event.get("step"));
        String cause = safeValue(event.get("cause"));
        String timeoutMs = safeValue(event.get("timeoutMs"));
        String waitedMs = safeValue(event.get("waitedMs"));
        String err = safeDiagnostic("web.await.err", event.get("err"));
        String causeLower = cause == null ? "" : cause.toLowerCase(Locale.ROOT);
        boolean nonOkFlag = truthy(event.get("nonOk")) || !isOkishCause(causeLower);

        String detail = firstNonBlank(
                safeDiagnostic("web.await.note", event.get("note")),
                safeDiagnostic("web.await.detail", event.get("detail")),
                safeDiagnostic("web.await.errMsg", event.get("errMsg")),
                safeDiagnostic("web.await.extra", event.get("extra")));
        if (detail != null && detail.length() > 240) {
            detail = detail.substring(0, 240) + "...";
        }

        sb.append("<tr data-ord='").append(globalIdx)
                .append("' data-stage='").append(escapeAttr(String.valueOf(stage).toLowerCase(Locale.ROOT)))
                .append("' data-engine='").append(escapeAttr(engine))
                .append("' data-cause='").append(escapeAttr(String.valueOf(cause).toLowerCase(Locale.ROOT)))
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

    private static void appendScript(StringBuilder sb, String rootId) {
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
    }

    private static boolean isOkishCause(String causeLower) {
        return "ok".equals(causeLower)
                || "done".equals(causeLower)
                || "done_null".equals(causeLower)
                || "missing_future".equals(causeLower)
                || causeLower.startsWith("skip_");
    }

    private static TimeoutFlags timeoutFlags(Map<String, Object> event) {
        String stage = String.valueOf(event.getOrDefault("stage", "")).toLowerCase(Locale.ROOT);
        String cause = String.valueOf(event.getOrDefault("cause", "")).toLowerCase(Locale.ROOT);

        boolean softTimeout = truthy(event.get("softTimeout"));
        boolean hardTimeout = truthy(event.get("hardTimeout"));
        if (!softTimeout && !hardTimeout) {
            boolean stageSoft = "soft".equals(stage);
            boolean stageHard = "hard".equals(stage);
            if ("budget_exhausted".equals(cause) || "timeout_soft".equals(cause)) {
                softTimeout = true;
            } else if ("timeout_hard".equals(cause)) {
                hardTimeout = true;
            } else if (cause.contains("timeout")) {
                if (stageSoft) {
                    softTimeout = true;
                } else {
                    hardTimeout = true;
                }
            }
        }

        boolean timeoutAny = truthy(event.get("timeout"))
                || softTimeout
                || hardTimeout
                || "timeout".equals(cause)
                || "budget_exhausted".equals(cause)
                || "timeout_soft".equals(cause)
                || "timeout_hard".equals(cause);
        return new TimeoutFlags(timeoutAny, softTimeout, hardTimeout);
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) {
                return number.longValue();
            }
            TraceStore.put("traceHtml.webAwait.suppressed.toLong", true);
            TraceStore.put("traceHtml.webAwait.suppressed.toLong.errorType", "invalid_number");
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            TraceStore.put("traceHtml.webAwait.suppressed.toLong", true);
            TraceStore.put("traceHtml.webAwait.suppressed.toLong.errorType", "invalid_number");
            return null;
        }
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value).replace("\n", " ").trim();
        return SafeRedactor.safeMessage(s, 800);
    }

    private static String safeDiagnostic(String key, Object value) {
        Object safe = SafeRedactor.diagnosticValue(key, value, 800);
        return safeValue(safe);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("\"", "&quot;");
    }

    private record TimeoutFlags(boolean any, boolean soft, boolean hard) {
    }

    private record AwaitStats(
            long total,
            long soft,
            long hard,
            long timeoutHard,
            long timeoutSoft,
            long timeoutAll,
            long nonOk,
            Long maxWaited) {

        static AwaitStats from(List<Map<String, Object>> events) {
            long total = events.size();
            long soft = 0;
            long hard = 0;
            long timeoutHard = 0;
            long timeoutSoft = 0;
            long timeoutAll = 0;
            long nonOk = 0;
            Long maxWaited = null;

            for (Map<String, Object> event : events) {
                String stage = String.valueOf(event.getOrDefault("stage", "")).toLowerCase(Locale.ROOT);
                String cause = String.valueOf(event.getOrDefault("cause", "")).toLowerCase(Locale.ROOT);
                if ("soft".equals(stage)) {
                    soft++;
                }
                if ("hard".equals(stage)) {
                    hard++;
                }

                TimeoutFlags flags = timeoutFlags(event);
                if (flags.any()) {
                    timeoutAll++;
                    if (flags.soft()) {
                        timeoutSoft++;
                    } else if (flags.hard()) {
                        timeoutHard++;
                    } else if ("soft".equals(stage)) {
                        timeoutSoft++;
                    } else if ("hard".equals(stage)) {
                        timeoutHard++;
                    }
                }

                if (truthy(event.get("nonOk")) || !isOkishCause(cause)) {
                    nonOk++;
                }
                Long waited = toLong(event.get("waitedMs"));
                if (waited != null && (maxWaited == null || waited > maxWaited)) {
                    maxWaited = waited;
                }
            }

            return new AwaitStats(total, soft, hard, timeoutHard, timeoutSoft, timeoutAll, nonOk, maxWaited);
        }

        boolean open() {
            return timeoutAll > 0 || nonOk > 0;
        }

        String timeoutHardPct() {
            return percent(timeoutHard, total);
        }

        String timeoutAllPct() {
            return percent(timeoutAll, total);
        }

        String nonOkPct() {
            return percent(nonOk, total);
        }

        private static String percent(long numerator, long denominator) {
            return denominator > 0 ? String.valueOf(Math.round((numerator * 100.0) / denominator)) : "0";
        }
    }
}
