package com.example.lms.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Hash-only principal identity retained with in-memory attachment metadata. */
public record AttachmentOwnerIdentity(String hash) {

    public AttachmentOwnerIdentity {
        hash = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("missing_owner");
        }
    }

    public static AttachmentOwnerIdentity forAdministrator(String username) {
        return hashed("admin", username);
    }

    public static AttachmentOwnerIdentity forAnonymous(String ownerKey) {
        return hashed("anon", ownerKey);
    }

    public static AttachmentOwnerIdentity forActor(String username, String ownerKey) {
        if (!isMissing(username)
                && !"anonymoususer".equalsIgnoreCase(username)
                && !"anonymous".equalsIgnoreCase(username)) {
            return forAdministrator(username);
        }
        return forAnonymous(ownerKey);
    }

    private static AttachmentOwnerIdentity hashed(String kind, String rawIdentity) {
        if (isMissing(rawIdentity)) {
            throw new IllegalArgumentException("missing_owner");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (kind + "\u0000" + rawIdentity).getBytes(StandardCharsets.UTF_8));
            return new AttachmentOwnerIdentity(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static boolean isMissing(String value) {
        return value == null || value.isBlank();
    }
}
