package com.example.lms.service.trace;

final class TraceHtmlLayout {
    private TraceHtmlLayout() {
    }

    static String renderPanelHeader(String title, String desc) {
        return "<div class='trace-panel-header'><strong>" + escape(title) + "</strong> "
                + "<span class='text-muted small'>" + escape(desc) + "</span></div>";
    }

    static String snapshotStyle() {
        return "<style>"
                + "body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:16px}"
                + ".text-muted{color:#666}"
                + ".small{font-size:12px}"
                + ".trace-mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}"
                + ".trace-section{border:1px solid #eee;border-radius:8px;padding:10px 12px;margin:12px 0}"
                + ".trace-panel-header{margin-bottom:8px}"
                + ".trace-kv code{background:#f7f7f7;padding:2px 4px;border-radius:4px}"
                + ".search-trace{display:block}"
                + ".trace-risk-ok{border-left:4px solid #2e7d32}"
                + ".trace-risk-warn{border-left:4px solid #ef6c00}"
                + ".trace-risk-high{border-left:4px solid #c62828}"
                + ".trace-risk-ok summary{color:#111}"
                + ".trace-risk-warn summary{color:#9a6a00}"
                + ".trace-risk-high summary{color:#9a0000}"
                + "</style>";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
