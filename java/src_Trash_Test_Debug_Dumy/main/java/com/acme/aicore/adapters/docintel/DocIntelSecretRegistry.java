package com.acme.aicore.adapters.docintel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Registry for Azure Document Intelligence secrets.
 *
 * <p>This helper lazily loads the endpoint and API key from a local JSON file at
 * <code>.secrets/azure_docintel.local.json</code>. It uses a fail‑soft strategy,
 * returning {@code null} values when secrets are unavailable or the file cannot be
 * parsed. Consumers should check {@link #available()} before calling {@link #endpoint()}
 * or {@link #apiKey()}.</p>
 */
public final class DocIntelSecretRegistry {
    private static final Path SECRET = Path.of(".secrets/azure_docintel.local.json");
    private static volatile String endpoint;
    private static volatile String apiKey;

    /** Returns {@code true} if both endpoint and API key are loaded and non‑null. */
    public static boolean available() {
        try {
            load();
            return endpoint != null && apiKey != null;
        } catch (Exception ignore) {
            return false;
        }
    }

    /** Returns the configured endpoint, or {@code null} if unavailable. */
    public static String endpoint() {
        load();
        return endpoint;
    }

    /** Returns the configured API key, or {@code null} if unavailable. */
    public static String apiKey() {
        load();
        return apiKey;
    }

    /** Lazily loads the secret file on first access. */
    private static void load() {
        if (endpoint != null && apiKey != null) return;
        try {
            if (!Files.exists(SECRET)) return;
            JsonNode n = new ObjectMapper().readTree(Files.readString(SECRET));
            endpoint = n.path("endpoint").asText(null);
            apiKey = n.path("apiKey").asText(null);
        } catch (Exception ignore) {
            // fail‑soft: leave endpoint/apiKey as null
        }
    }

    // Prevent instantiation.
    private DocIntelSecretRegistry() {}
}