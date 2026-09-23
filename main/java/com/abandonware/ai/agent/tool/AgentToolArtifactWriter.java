package com.abandonware.ai.agent.tool;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class AgentToolArtifactWriter {
    private final ObjectMapper objectMapper;

    public AgentToolArtifactWriter() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            this.objectMapper.findAndRegisterModules();
        } catch (Throwable ignore) {
            traceSuppressed("mapperModules", ignore);
            // best-effort
        }
    }

    public Map<String, Object> write(String family, String idHint, Object payload) {
        try {
            String safeFamily = safeSegment(family, "tool");
            String safeId = safeSegment(idHint, "artifact");
            Path dir = Path.of("build", "reports", "abandon", safeFamily);
            Files.createDirectories(dir);
            Object sanitized = SafeRedactor.diagnosticValue("artifactPayload", payload, 4000);
            byte[] bytes = objectMapper.writeValueAsBytes(sanitized);
            String hash = sha256(bytes);
            Path file = dir.resolve(Instant.now().toEpochMilli() + "-" + safeId + "-" + hash.substring(0, 12) + ".json");
            Files.write(file, bytes);

            Map<String, Object> out = new LinkedHashMap<>();
            String absolutePath = file.toAbsolutePath().toString();
            out.put("artifactId", hash.substring(0, 12));
            out.put("artifactPathHash", SafeRedactor.hashValue(absolutePath));
            out.put("artifactPathLength", absolutePath.length());
            out.put("sha256", hash);
            out.put("sizeBytes", bytes.length);
            out.put("family", safeFamily);
            return out;
        } catch (Exception ex) {
            throw ToolInvocationException.failed("artifact_write_failed");
        }
    }

    public byte[] toJsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            traceSuppressed("toJsonBytes", ex);
            return String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String safeSegment(String value, String fallback) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String safe = raw.replaceAll("[^a-z0-9._-]+", "-");
        while (safe.contains("--")) {
            safe = safe.replace("--", "-");
        }
        safe = safe.replaceAll("^-|-$", "");
        return safe.isBlank() ? fallback : safe;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.put("agent.toolArtifact.json.suppressed", true);
        TraceStore.put("agent.toolArtifact.json.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.toolArtifact.json.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
    }
}
