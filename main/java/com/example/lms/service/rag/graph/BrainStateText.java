package com.example.lms.service.rag.graph;

import org.apache.commons.codec.digest.DigestUtils;

import java.util.Locale;

final class BrainStateText {

    private BrainStateText() {
    }

    static String hash12(String value) {
        return DigestUtils.sha256Hex(value == null ? "" : value).substring(0, 12);
    }

    static String normalizeDomain(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank()) {
            return "GENERAL";
        }
        return v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
    }

    static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
