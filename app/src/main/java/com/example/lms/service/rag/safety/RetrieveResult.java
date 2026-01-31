package com.example.lms.service.rag.safety;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.safety.RetrieveResult
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.safety.RetrieveResult
role: config
*/
public class RetrieveResult<T> {
    private final List<T> docs;
    private final String status; // OK, STALE, EMPTY, ERROR
    private final String errorCode;

    public RetrieveResult(List<T> docs, String status, String errorCode) {
        this.docs = docs == null ? Collections.emptyList() : docs;
        this.status = status == null ? "OK" : status;
        this.errorCode = errorCode;
    }

    public List<T> getDocs() { return docs; }
    public String getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}