package com.example.lms.service.rag.safety;

import java.util.*;

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