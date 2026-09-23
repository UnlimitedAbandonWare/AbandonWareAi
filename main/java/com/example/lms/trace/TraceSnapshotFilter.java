package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;

/**
 * Optional servlet filter adapter that exports an allowlisted trace snapshot
 * after the downstream request chain has populated TraceStore.
 */
public class TraceSnapshotFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String SESSION_ID_HEADER = "X-Session-Id";

    private final ObjectProvider<TraceSnapshotExporter> exporterProvider;

    public TraceSnapshotFilter(ObjectProvider<TraceSnapshotExporter> exporterProvider) {
        this.exporterProvider = exporterProvider;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            exportAfterChain(request);
        }
    }

    private void exportAfterChain(ServletRequest request) {
        try {
            TraceSnapshotExporter exporter = exporterProvider == null ? null : exporterProvider.getIfAvailable();
            if (exporter == null || !(request instanceof HttpServletRequest httpRequest)) {
                return;
            }
            exporter.exportCurrentTrace(
                    firstNonBlank(httpRequest.getHeader(REQUEST_ID_HEADER), TraceStore.getString("requestId")),
                    firstNonBlank(httpRequest.getHeader(SESSION_ID_HEADER), TraceStore.getString("sessionId"))
            );
        } catch (RuntimeException ignore) {
            TraceStore.put("trace.snapshot.filter.failed",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = trimToNull(first);
        if (normalizedFirst != null) {
            return normalizedFirst;
        }
        return trimToNull(second);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
