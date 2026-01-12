package com.example.lms.service.telemetry;

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
        sb.append("\"requestId\":\"").append(escape(requestId)).append("\",");
        sb.append("\"query\":\"").append(escape(query)).append("\",");
        sb.append("\"cell\":\"").append(escape(cell)).append("\",");
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
}