package com.example.lms.service.telemetry;

import com.example.lms.trace.SafeRedactor;

import java.util.Map;
import java.util.LinkedHashMap;



public class DecisionTraceEvent {
    public String requestId;
    public String query;
    public String cell;
    public Map<String,Object> signals = new LinkedHashMap<>();
    public Map<String,Object> plan = new LinkedHashMap<>();
    public String[] handlers;
    public Map<String,Object> fuse = new LinkedHashMap<>();
    public Map<String,Object> latency = new LinkedHashMap<>();

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"requestIdHash\":\"").append(escape(hashOrEmpty(requestId))).append("\",");
        sb.append("\"queryHash\":\"").append(escape(hashOrEmpty(query))).append("\",");
        sb.append("\"queryLength\":").append(query == null ? 0 : query.length()).append(',');
        sb.append("\"queryPresent\":").append(query != null && !query.isBlank()).append(',');
        sb.append("\"cell\":\"").append(escape(SafeRedactor.safeMessage(cell, 80))).append("\",");
        // Minimal JSON; defer to real JSON lib in production
        sb.append("\"signals\":{},");
        sb.append("\"plan\":{},");
        sb.append("\"handlers\":[],");
        sb.append("\"fuse\":{},");
        sb.append("\"latency\":{}");
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n"," ");
    }

    private static String hashOrEmpty(String s) {
        String hash = SafeRedactor.hashValue(s);
        return hash == null ? "" : hash;
    }
}
