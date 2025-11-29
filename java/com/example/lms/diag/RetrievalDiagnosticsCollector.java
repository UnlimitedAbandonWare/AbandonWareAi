// src/main/java/com/example/lms/diag/RetrievalDiagnosticsCollector.java
package com.example.lms.diag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;




/**
 * Collects per-request diagnostics for the retrieval pipeline.  A new
 * collector is typically created and bound to a single request thread via
 * dependency injection.  Each call to {@link #withSpan(String, Supplier)}
 * executes the supplied work under a named {@link StageSpan} and records
 * timing, hit counts, and any warnings or errors thrown by the supplier.
 * After all spans have been recorded, callers can obtain a summary line via
 * {@link #summarize()} or dump detailed spans via {@link #dump()}.
 */
@Slf4j
@Component
public class RetrievalDiagnosticsCollector {

    private final ThreadLocal<Deque<StageSpan>> spans = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<List<StageSpan>> completed = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<AtomicLong> startNanos = ThreadLocal.withInitial(() -> new AtomicLong(System.nanoTime()));

    /**
     * Execute the given supplier under a stage with the provided name.
     * Any exceptions thrown by the supplier are propagated; however the
     * resulting span will record the exception class name as an error.  The
     * return value of the supplier is passed through.  If the result is a
     * collection, its size is used as the hit count.
     *
     * @param name the logical name of the stage (e.g. "Web", "Vector")
     * @param supplier the code to execute
     * @param <T> the return type
     * @return the supplier's return value
     */
    @SuppressWarnings("unchecked")
    public <T> T withSpan(String name, Supplier<T> supplier) {
        StageSpan span = new StageSpan(name);
        spans.get().push(span);
        try {
            T result = supplier.get();
            int hits = 0;
            if (result instanceof java.util.Collection) {
                hits = ((java.util.Collection<?>) result).size();
            }
            span.finish(hits);
            return result;
        } catch (Exception ex) {
            span.addError(ex.getClass().getSimpleName() + ":" + ex.getMessage());
            span.finish(0);
            throw ex;
        } finally {
            // Pop current span and add to completed list
            spans.get().pop();
            completed.get().add(span);
        }
    }

    /**
     * Produce a one-line summary of all collected spans.  The summary will
     * include the total elapsed time since the first span started, and list
     * each stage with its elapsed time, hit count, warning count and error count.
     * This summary is suitable for INFO level logging.
     *
     * @return summary string
     */
    public String summarize() {
        long totalMs = (System.nanoTime() - startNanos.get().get()) / 1_000_000L;
        StringBuilder sb = new StringBuilder();
        sb.append("diag totalMs=").append(totalMs).append(" stages=[");
        List<StageSpan> spansList = completed.get();
        for (int i = 0; i < spansList.size(); i++) {
            StageSpan s = spansList.get(i);
            sb.append(s.getName()).append(":" + s.getElapsedMs() + "ms/" + s.getHitCount());
            if (!s.getWarnings().isEmpty()) sb.append(" warn").append(s.getWarnings().size());
            if (!s.getErrors().isEmpty()) sb.append(" err").append(s.getErrors().size());
            if (i < spansList.size() - 1) sb.append("; ");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Dump the detailed spans for DEBUG logging.  Each span's toString()
     * representation is concatenated.
     */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (StageSpan s : completed.get()) {
            sb.append(s.toString()).append("\n");
        }
        return sb.toString();
    }
}