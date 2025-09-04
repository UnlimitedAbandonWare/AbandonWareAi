package com.acme.aicore.adapters.search.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;

/**
 * Loads Azure Cognitive Search credentials from a local secrets file.  The
 * registry attempts to read a JSON file located at the path
 * {@code .secrets/azure_search.local.json} relative to the project root.
 * This file must not be committed to source control and must be ignored by
 * any packaging logic.  The expected structure of the JSON file is:
 *
 * <pre>
 * {
 *   "endpoint": "https://<service>.search.windows.net",
 *   "index":    "<index-name>",
 *   "adminKey": "<ADMIN>",
 *   "queryKey": "<QUERY>"
 * }
 * </pre>
 *
 * When the file is missing or cannot be parsed, the registry marks itself
 * as unloaded.  Consumers must check {@link #isLoaded()} prior to
 * attempting to retrieve any key.  Keys are lazily loaded on first access
 * so that repeated calls do not re-read the file unnecessarily.  All
 * values are returned as-is; it is the caller's responsibility to avoid
 * exposing them via logs or external side effects.
 */
public final class SecretKeyRegistry {

    private static final String SECRETS_PATH = ".secrets/azure_search.local.json";
    private static String endpoint;
    private static String index;
    private static String adminKey;
    private static String queryKey;
    private static boolean loaded = false;

    private SecretKeyRegistry() {}

    /**
     * Lazily load the secrets from disk.  If the file does not exist or
     * cannot be parsed, the registry remains unloaded.  This method is
     * idempotent; subsequent invocations after successful loading are
     * no-ops.
     */
    private static synchronized void load() {
        if (loaded) return;
        try {
            File file = new File(SECRETS_PATH);
            if (!file.exists()) {
                loaded = false;
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(file);
            endpoint = textOrNull(root, "endpoint");
            index    = textOrNull(root, "index");
            adminKey = textOrNull(root, "adminKey");
            queryKey = textOrNull(root, "queryKey");
            loaded = endpoint != null && index != null && queryKey != null
                     && !endpoint.isBlank() && !index.isBlank() && !queryKey.isBlank();
        } catch (IOException ex) {
            // Do not expose exception details; simply mark as unloaded
            loaded = false;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String v = node.get(field).asText();
            return (v != null && !v.isBlank()) ? v : null;
        }
        return null;
    }

    /**
     * @return true if credentials have been loaded successfully.
     */
    public static boolean isLoaded() {
        if (!loaded) load();
        return loaded;
    }

    /**
     * @return the endpoint URL or {@code null} when not loaded.
     */
    public static String getEndpoint() {
        return isLoaded() ? endpoint : null;
    }

    /**
     * @return the index name or {@code null} when not loaded.
     */
    public static String getIndex() {
        return isLoaded() ? index : null;
    }

    /**
     * @return the admin API key or {@code null} when not loaded.
     */
    public static String getAdminKey() {
        return isLoaded() ? adminKey : null;
    }

    /**
     * @return the query API key or {@code null} when not loaded.
     */
    public static String getQueryKey() {
        return isLoaded() ? queryKey : null;
    }
}