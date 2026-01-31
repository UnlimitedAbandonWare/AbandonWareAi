// src/main/java/com/example/lms/trace/EbnaDetector.java
package com.example.lms.trace;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;



/**
 * Utility to detect EBNA (Evidence-But-No-Answer) situations.  It
 * tracks the number of documents retrieved for the current request and
 * evaluates the assistant’s answer to determine whether it appears
 * complete.  When at least three documents were retrieved but the
 * answer is very short or indicates uncertainty, an EBNA event is
 * emitted.  Regardless of outcome, a summary event recording the
 * evidence count, answer length and EBNA flag is always sent.
 */
public final class EbnaDetector {
    // Per-thread accumulator of retrieved document count.  Each request
    // runs on a separate thread under Reactor context so this thread local
    // suffices for accumulating counts until the answer is produced.
    private static final ThreadLocal<AtomicInteger> retrievedCount = ThreadLocal.withInitial(AtomicInteger::new);

    private EbnaDetector() {}

    /**
     * Increment the count of retrieved documents for the current request.
     * This should be called by the retrieval aspect whenever a search or
     * vector retriever returns results.
     *
     * @param n the number of documents retrieved
     */
    public static void incRetrieved(int n) {
        retrievedCount.get().addAndGet(n);
    }

    /**
     * Evaluate the provided answer preview and emit summary and EBNA events
     * as appropriate.  The number of retrieved documents is reset after
     * evaluation.
     *
     * @param answerPreview a preview of the assistant’s answer
     */
    public static void checkAndEmit(String answerPreview) {
        int docs = retrievedCount.get().get();
        int ansChars = answerPreview == null ? 0 : answerPreview.trim().length();
        boolean looksNoAnswer = ansChars < 80
                || answerPreview.trim().endsWith("?")
                || answerPreview.contains("모르")
                || answerPreview.toLowerCase().contains("i don't know");
        boolean ebna = (docs >= 3) && looksNoAnswer;
        TraceLogger.emit("summary", "summary", Map.of(
                "docs", docs,
                "answer_chars", ansChars,
                "ebna", ebna
        ));
        if (ebna) {
            TraceLogger.emit("ebna", "summary", Map.of(
                    "reason", "evidence_but_no_answer",
                    "docs", docs,
                    "answer_chars", ansChars
            ));
        }
        retrievedCount.get().set(0);
    }
}