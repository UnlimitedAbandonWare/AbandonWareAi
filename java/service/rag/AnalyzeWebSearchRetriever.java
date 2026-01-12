package service.rag;

import trace.TraceContext;
/**
 * Minimal retriever with timeout field, to keep compatibility.
 */
public class AnalyzeWebSearchRetriever {
    private int timeoutMs = 1800;
    private int webTopK = 10;
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public void setWebTopK(int k) { this.webTopK = k; }
}