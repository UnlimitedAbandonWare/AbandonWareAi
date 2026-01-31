// src/main/java/com/example/lms/diag/StageSpan.java
package com.example.lms.diag;

import java.util.ArrayList;
import java.util.List;



/**
 * Captures diagnostics for a single stage of the retrieval or processing
 * pipeline.  Each span records the name of the stage, the start and end
 * times in nanoseconds, the number of hits (e.g. results returned), and
 * any warnings or errors.  Consumers can calculate the elapsed time in
 * milliseconds via {@link #getElapsedMs()}.
 */
public class StageSpan {
    private final String name;
    private final long startNanos;
    private long endNanos;
    private int hitCount;
    private final List<String> warnings;
    private final List<String> errors;

    public StageSpan(String name) {
        this.name = name;
        this.startNanos = System.nanoTime();
        this.warnings = new ArrayList<>();
        this.errors = new ArrayList<>();
    }

    public void finish(int hitCount) {
        this.hitCount = hitCount;
        this.endNanos = System.nanoTime();
    }

    public void addWarning(String warn) {
        if (warn != null && !warn.isBlank()) {
            warnings.add(warn);
        }
    }

    public void addError(String err) {
        if (err != null && !err.isBlank()) {
            errors.add(err);
        }
    }

    public String getName() {
        return name;
    }

    public long getElapsedMs() {
        if (endNanos == 0L) return 0L;
        return (endNanos - startNanos) / 1_000_000L;
    }

    public int getHitCount() {
        return hitCount;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return name + ":" + getElapsedMs() + "ms/" + hitCount + "hits warn=" + warnings.size() + " err=" + errors.size();
    }
}