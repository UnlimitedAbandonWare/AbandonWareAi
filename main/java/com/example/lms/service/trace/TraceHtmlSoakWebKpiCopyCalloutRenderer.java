package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class TraceHtmlSoakWebKpiCopyCalloutRenderer {

    private static final String RUN_PREFIX = "web.failsoft.soakKpiJson.runId.";
    private static final String LAST_KEY = "web.failsoft.soakKpiJson.last";

    private TraceHtmlSoakWebKpiCopyCalloutRenderer() {
    }

    static String render(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) {
            return "";
        }

        List<Map.Entry<Long, String>> lines = new ArrayList<>();
        for (Map.Entry<String, Object> entry : extraMeta.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (!entry.getKey().startsWith(RUN_PREFIX)) {
                continue;
            }
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (isNonBlank(value)) {
                lines.add(new AbstractMap.SimpleEntry<>(parseRunId(entry.getKey()), value));
            }
        }

        lines.sort(Comparator.comparingLong(Map.Entry::getKey));
        if (lines.size() > 3) {
            lines = lines.subList(lines.size() - 3, lines.size());
        }

        if (lines.isEmpty()) {
            String last = getString(extraMeta, LAST_KEY);
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
                "<div style='font-size:12px; color:#555; margin-bottom:8px;'>TraceStore keeps the latest 2-3 soak KPI lines for DC-0..DC-4 checks.</div>");

        sb.append("<div style='font-size:12px; color:#666; margin-bottom:8px;'>");
        sb.append("Soak poll: <span class='mono'>/internal/probe/websoak-kpi/last?format=line</span> ");
        sb.append("(<span class='mono'>X-Probe-Key</span> recommended; avoid <span class='mono'>?key=</span> in access logs) ");
        sb.append("- UI: <span class='mono'>/internal/probe/websoak-kpi/ui</span>");
        sb.append("</div>");

        int idx = 0;
        for (Map.Entry<Long, String> line : lines) {
            idx++;
            String rawJson = line.getValue() == null ? "" : line.getValue();
            String json = SafeRedactor.safeMessage(rawJson, 4096);
            String id = "soak-web-kpi-" + Math.abs((json + "|" + idx).hashCode());
            sb.append("<div style='display:flex; align-items:center; gap:8px; margin:6px 0 2px 0;'>");
            sb.append("<button type='button' data-copy-target='").append(escapeAttr(id))
                    .append("' style='padding:2px 8px; font-size:12px; cursor:pointer;'>Copy</button>");
            if (line.getKey() >= 0) {
                sb.append("<span style='font-size:12px; color:#777;'>runId=").append(line.getKey()).append("</span>");
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
                    try{document.execCommand('copy');}catch(e){
                      if(window.console&&console.debug){console.debug('trace copy fallback skipped stage=execCommand');}
                    }
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

    private static long parseRunId(String key) {
        try {
            return Long.parseLong(key.substring(RUN_PREFIX.length()));
        } catch (NumberFormatException ex) {
            TraceStore.put("traceHtml.soakWebKpi.suppressed.parseRunId", true);
            TraceStore.put("traceHtml.soakWebKpi.suppressed.parseRunId.errorType", "invalid_number");
            return -1L;
        }
    }

    private static String getString(Map<String, Object> meta, String key) {
        return meta == null ? null : String.valueOf(meta.get(key));
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank() && !"null".equals(s);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("\"", "&quot;");
    }
}
