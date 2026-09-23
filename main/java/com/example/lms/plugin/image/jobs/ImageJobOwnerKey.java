package com.example.lms.plugin.image.jobs;

import com.example.lms.trace.SafeRedactor;

public final class ImageJobOwnerKey {
    private ImageJobOwnerKey() {
    }

    public static String hash(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return null;
        }
        return SafeRedactor.hashValue(ownerKey.trim());
    }

    public static boolean matches(String expectedHash, String ownerKey) {
        String current = hash(ownerKey);
        return expectedHash != null && current != null && expectedHash.equals(current);
    }
}
