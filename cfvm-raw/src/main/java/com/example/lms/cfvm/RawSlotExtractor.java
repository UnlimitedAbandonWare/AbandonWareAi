package com.example.lms.cfvm;

import java.util.List;
/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.RawSlotExtractor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.RawSlotExtractor
role: config
*/
public interface RawSlotExtractor {
    List<RawSlot> extract(Throwable ex, RawSlot.Stage stage, String sessionId);
}