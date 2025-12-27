package com.abandonware.ai.example.lms.cfvm;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;



/** A minimal immutable record of a raw error/retrieval slot. */
@Builder
public record RawSlot(
        String sessionId,
        Stage stage,
        String code,          // short code, e.g. "MissingSymbol"
        String path,          // class/file or handler
        String message,       // trimmed message
        Map<String,String> tags,
        Instant ts
) {
    public enum Stage { ANALYZE, SELF_ASK, WEB, VECTOR, KG, ANSWER, BUILD, RUNTIME }
}