package com.example.lms.service.rag.overdrive;

public class OverdriveGuard {
    /** 
     * Returns true when query is short/sparse so we should switch to expansion mode.
     * Heuristic only; can be wired to telemetry later.
     */
    public boolean shouldActivate(String query){
        if (query == null) return false;
        String q = query.trim();
        // Activate for very short queries or ones with few non-stopword tokens
        int len = q.codePointCount(0, q.length());
        return len > 0 && len < 18;
    }
}