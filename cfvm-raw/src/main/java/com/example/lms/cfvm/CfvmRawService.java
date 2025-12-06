package com.example.lms.cfvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * CFVM-Raw service - collect build error slots, learn a 3x3 matrix,
 * and expose recent error-patterns for downstream planners.
 */
public final class CfvmRawService {

    private static final Logger log = LoggerFactory.getLogger(CfvmRawService.class);

    private final RawMatrixBuffer buffer;
    private final RawSlotExtractor extractor;
    private final Deque<String> recentCodes = new ArrayDeque<>();

    public CfvmRawService() {
        this(512, new BuildLogSlotExtractor());
    }

    public CfvmRawService(int capacity, RawSlotExtractor extractor) {
        this.buffer = new RawMatrixBuffer(capacity);
        this.extractor = Objects.requireNonNull(extractor);
    }

    /** Ingest a free-form build log text. */
    public synchronized void ingest(String logText) {
        for (RawSlot s : extractor.extract(logText)) {
            buffer.push(s);
            remember(s.code());
        }
        // Update matrix with mild decay so history still influences
        buffer.fit(0.92);
    }

    /** Lightweight single-line ingestion helper. */
    public synchronized void mark(String code, RawSlot.Severity severity, String message) {
        buffer.push(new RawSlot(Instant.now(), code, message, severity));
        remember(code);
        buffer.fit(0.92);
    }

    public synchronized double[][] weights(double temperature) {
        return buffer.boltzmann(temperature);
    }

    /** Most recent distinct error codes (MRU). */
    public synchronized List<String> recentCodes(int topK) {
        final LinkedHashSet<String> uniq = new LinkedHashSet<>(recentCodes);
        List<String> out = new ArrayList<>(uniq);
        if (out.size() > topK) {
            return out.subList(0, topK);
        }
        return out;
    }

    private void remember(String code) {
        recentCodes.remove(code);
        recentCodes.addFirst(code);
        while (recentCodes.size() > 32) recentCodes.removeLast();
    }
}