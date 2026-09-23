package com.example.lms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Cross-process launch serialization and exact execution registration for the existing local manager. */
final class ManagedOllamaEndpoint implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FileChannel channel;
    private final FileLock lock;
    private final Path record;
    private final String key;
    private boolean closed;

    private ManagedOllamaEndpoint(FileChannel channel, FileLock lock, Path record, String key) {
        this.channel = channel; this.lock = lock; this.record = record; this.key = key;
    }

    static ManagedOllamaEndpoint acquire(Path directory, String host, String gpu) throws IOException {
        URI origin = URI.create(host.contains("://") ? host : "http://" + host);
        if (!"http".equals(origin.getScheme()) || origin.getUserInfo() != null
                || !java.util.Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(origin.getHost())) {
            throw new IOException("managed_endpoint_requires_loopback");
        }
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (origin.getPort() + "|" + gpu + "|cuda|vulkan_disabled").getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Path root = directory.toAbsolutePath().normalize();
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw new IOException("managed_registry_reparse_path");
        }
        Files.createDirectories(root);
        Path lockPath = root.resolve("ollama-role-" + key + ".lck");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("managed_registry_reparse_path");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("managed_endpoint_start_in_progress");
            return new ManagedOllamaEndpoint(channel, lock, root.resolve("ollama-role-" + key + ".json"), key);
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("managed_endpoint_start_in_progress", failure);
        }
    }

    // Stable per configured role: a busy port is held, never guessed to belong to this application.
    String host() {
        return "127.0.0.1:" + (20000 + Integer.parseInt(key.substring(0, 4), 16) % 20000);
    }

    boolean registered(String endpoint, long pid, long startedAt) throws IOException {
        if (pid <= 0 || startedAt <= 0 || !Files.isRegularFile(record)) return false;
        if (Files.isSymbolicLink(record) || Files.size(record) > 4096) return false;
        var row = JSON.readTree(Files.readAllBytes(record));
        return key.equals(row.path("selectionHash").asText()) && endpoint.equals(row.path("host").asText())
                && pid == row.path("pid").asLong() && startedAt == row.path("startedAtEpochMs").asLong();
    }

    void register(String endpoint, long pid, long startedAt) throws IOException {
        if (pid <= 0 || startedAt <= 0) return; // Unknown identity never earns reusable ownership.
        if (Files.isSymbolicLink(record)) throw new IOException("managed_registry_reparse_path");
        Files.write(record, JSON.writeValueAsBytes(Map.of("selectionHash", key, "host", endpoint,
                "pid", pid, "startedAtEpochMs", startedAt)), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { channel.close(); }
    }
}
