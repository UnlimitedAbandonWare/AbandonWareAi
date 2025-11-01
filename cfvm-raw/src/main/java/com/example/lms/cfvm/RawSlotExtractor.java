package com.example.lms.cfvm;

import java.util.List;

/** Extracts raw slots from a text blob (e.g., a build log). */
public interface RawSlotExtractor {
    List<RawSlot> extract(String text);
}