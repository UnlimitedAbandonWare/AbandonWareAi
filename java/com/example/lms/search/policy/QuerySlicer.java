package com.example.lms.search.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Utility: slice a potentially long/multi-sentence query into overlapping windows.
 */
public final class QuerySlicer {

    // Sentence boundary split (Latin & CJK punctuation + newlines)
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。！？])\\s+|\\R+");

    private QuerySlicer() {
    }

    public static List<String> slice(String text, int windowSentences, int overlapSentences, int maxSlices) {
        String in = Objects.toString(text, "").trim();
        if (in.isBlank()) {
            return List.of();
        }
        int window = Math.max(1, windowSentences);
        int overlap = Math.max(0, overlapSentences);
        if (overlap >= window) {
            overlap = Math.max(0, window - 1);
        }
        int step = Math.max(1, window - overlap);

        String[] raw = SENTENCE_SPLIT.split(in);
        List<String> sentences = new ArrayList<>();
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isBlank()) {
                sentences.add(t);
            }
        }

        // If we don't have enough sentence boundaries, don't emit slices.
        if (sentences.size() <= 1) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (int start = 0; start < sentences.size(); start += step) {
            int end = Math.min(sentences.size(), start + window);
            if (end <= start) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(sentences.get(i));
            }
            String slice = sb.toString().trim();
            if (slice.length() < 8) {
                continue;
            }
            out.add(slice);
            if (maxSlices > 0 && out.size() >= maxSlices) {
                break;
            }
            if (end == sentences.size()) {
                break;
            }
        }

        return out;
    }
}
