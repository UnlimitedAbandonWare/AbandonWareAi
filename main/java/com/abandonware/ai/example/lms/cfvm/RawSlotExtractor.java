package com.abandonware.ai.example.lms.cfvm;

import java.util.List;



public interface RawSlotExtractor {
    List<RawSlot> extract(Throwable ex, RawSlot.Stage stage, String sessionId);
}