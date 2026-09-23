package com.example.lms.debug.ai;

/**
 * One redacted, scalar debug observation used by the AI debug metrics view.
 */
public record DebugAiRawSlot(
        String eventId,
        long tsMs,
        String probe,
        String layer,
        String failureClass,
        String fingerprintHash,
        String sidHash,
        String traceIdHash,
        String requestIdHash,
        String where,
        long latencyMs,
        String toolId,
        String planId,
        String verificationCommandHash,
        String result,
        String severity,
        long queryRewriteSubModelCount,
        long queryRewriteBranchTitleCount,
        long queryRewriteBranchTitleHashCount,
        long queryRewriteBranchAxisCount,
        long queryRewritePaddedCount) {
}
