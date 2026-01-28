package ai.abandonware.nova.orch.storage;

// MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_FILE_IMPL_V2

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed degraded storage ("outbox") for {@link PendingMemoryEvent}.
 *
 * <p>
 * Supports two formats:
 * </p>
 * <ul>
 * <li><b>Directory mode</b> (preferred): one JSON file per event, allowing
 * claim/ack with partial batch success.</li>
 * <li><b>JSONL mode</b> (legacy): a single JSONL file. Claim/ack is implemented
 * via a side "inflight" JSONL file.</li>
 * </ul>
 */
public class FileDegradedStorage implements DegradedStorageWithAck {

    private enum StorageMode {
        DIRECTORY, JSONL
    }

    private static final String DIR_PENDING_GLOB = "*.json";
    private static final String DIR_INFLIGHT_GLOB = "*.inflight";
    private static final String DIR_INFLIGHT_SUFFIX = ".inflight";

    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    private final StorageMode mode;
    private final Path basePath;
    private final Path jsonlPending;
    private final Path jsonlInflight;
    private final Path jsonlQuarantine;

    private final boolean quarantineEnabled;
    private final int maxAttempts;
    private final String quarantineDirName;
    private final Path quarantineDir;

    private final long ttlSeconds;
    private final int maxFiles;
    private final long maxBytes;
    private final long inflightStaleSeconds;
    private final boolean enforceOnWrite;

    // Counters (best-effort, in-memory only)
    private final AtomicLong ackTotal = new AtomicLong();
    private final AtomicLong nackTotal = new AtomicLong();
    private final AtomicLong releaseTotal = new AtomicLong();
    private final AtomicLong quarantineTotal = new AtomicLong();
    private final AtomicLong droppedExpiredTotal = new AtomicLong();
    private final AtomicLong droppedByLimitTotal = new AtomicLong();
    private final AtomicLong parseErrorTotal = new AtomicLong();
    private final AtomicLong lastSweepEpochMs = new AtomicLong();

    // Envelope used for both JSONL and directory payloads
    private record OutboxEnvelope(
            String id,
            long createdAtEpochMs,
            int attempts,
            Long lastAttemptEpochMs,
            String lastError,
            PendingMemoryEvent event) {
    }

    public FileDegradedStorage(NovaOrchestrationProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        NovaOrchestrationProperties.DegradedStorageProps p = props != null && props.getDegradedStorage() != null
                ? props.getDegradedStorage()
                : new NovaOrchestrationProperties.DegradedStorageProps();

        this.ttlSeconds = p.getTtlSeconds();
        this.maxFiles = p.getMaxFiles();
        this.maxBytes = p.getMaxBytes();
        this.inflightStaleSeconds = p.getInflightStaleSeconds();
        this.enforceOnWrite = p.isEnforceOnWrite();

        // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_QUARANTINE_INIT_V1
        this.maxAttempts = p.getMaxAttempts();
        this.quarantineEnabled = p.isQuarantineEnabled() && this.maxAttempts > 0;
        this.quarantineDirName = (p.getQuarantineDirName() == null || p.getQuarantineDirName().isBlank())
                ? "quarantine"
                : p.getQuarantineDirName().trim();

        Path configured = Paths.get(p.getPath()).toAbsolutePath().normalize();
        this.mode = resolveMode(configured, p.getFormat());

        if (mode == StorageMode.DIRECTORY) {
            this.basePath = configured;
            this.jsonlPending = null;
            this.jsonlInflight = null;
            this.jsonlQuarantine = null;
            this.quarantineDir = basePath.resolve(quarantineDirName);
            try {
                Files.createDirectories(basePath);
                if (quarantineEnabled) {
                    Files.createDirectories(quarantineDir);
                }
            } catch (IOException e) {
                // fail-soft
            }
        } else {
            this.basePath = configured.getParent() != null ? configured.getParent() : Paths.get(".").toAbsolutePath();
            this.jsonlPending = configured;
            this.jsonlInflight = configured.resolveSibling(configured.getFileName().toString() + ".inflight");
            this.jsonlQuarantine = configured.resolveSibling(configured.getFileName().toString() + ".quarantine");
            this.quarantineDir = basePath.resolve(quarantineDirName);
            try {
                if (jsonlPending.getParent() != null) {
                    Files.createDirectories(jsonlPending.getParent());
                }
            } catch (IOException e) {
                // fail-soft
            }
        }
    }

    private static StorageMode resolveMode(Path configured, String format) {
        String f = (format == null ? "auto" : format).trim().toLowerCase();
        if (f.equals("dir") || f.equals("directory"))
            return StorageMode.DIRECTORY;
        if (f.equals("jsonl") || f.equals("file"))
            return StorageMode.JSONL;

        // auto
        String name = configured.getFileName() != null ? configured.getFileName().toString() : "";
        if (name.endsWith(".jsonl") || name.endsWith(".log") || name.endsWith(".txt")) {
            return StorageMode.JSONL;
        }
        // If path already exists, use its type
        try {
            if (Files.exists(configured)) {
                return Files.isDirectory(configured) ? StorageMode.DIRECTORY : StorageMode.JSONL;
            }
        } catch (Exception ignored) {
        }

        // Heuristic: if the last segment contains a dot -> likely a file
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? StorageMode.JSONL : StorageMode.DIRECTORY;
    }

    @Override
    public void putPending(PendingMemoryEvent event) {
        if (event == null)
            return;
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            OutboxEnvelope env = new OutboxEnvelope(
                    newId(),
                    event.timestamp() != null ? event.timestamp().toEpochMilli() : now,
                    0,
                    null,
                    null,
                    event);

            if (mode == StorageMode.DIRECTORY) {
                writeEnvelopeFile(env);
            } else {
                appendJsonl(jsonlPending, env);
            }

            if (enforceOnWrite) {
                sweepInternal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Legacy interface: remove and return up to {@code max} events.
     *
     * <p>
     * Implemented by claiming then ACKing all claimed items.
     * </p>
     */
    @Override
    public List<PendingMemoryEvent> drain(int max) {
        List<ClaimedPending> claimed = claim(max);
        List<PendingMemoryEvent> out = new ArrayList<>();
        for (ClaimedPending c : claimed) {
            if (c != null && c.event() != null)
                out.add(c.event());
        }
        for (ClaimedPending c : claimed) {
            if (c != null) {
                try {
                    ack(c.token());
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    @Override
    public List<ClaimedPending> claim(int max) {
        if (max <= 0)
            return List.of();
        lock.lock();
        try {
            sweepInternal();
            if (mode == StorageMode.DIRECTORY) {
                return claimDirectory(max);
            }
            return claimJsonl(max);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void ack(String token) {
        if (token == null || token.isBlank())
            return;
        lock.lock();
        try {
            if (mode == StorageMode.DIRECTORY) {
                Path inflight = basePath.resolve(token);
                safeDelete(inflight);
            } else {
                removeFromJsonlById(jsonlInflight, token);
            }
            ackTotal.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(String token) {
        if (token == null || token.isBlank())
            return;
        lock.lock();
        try {
            if (mode == StorageMode.DIRECTORY) {
                Path inflight = basePath.resolve(token);
                Path pending = toPendingPath(inflight);
                safeMove(inflight, pending);
            } else {
                Optional<OutboxEnvelope> envOpt = removeAndReturnFromJsonlById(jsonlInflight, token);
                envOpt.ifPresent(env -> appendJsonl(jsonlPending, env));
            }
            releaseTotal.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void nack(String token, String error) {
        if (token == null || token.isBlank())
            return;
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (mode == StorageMode.DIRECTORY) {
                Path inflight = basePath.resolve(token);
                Optional<OutboxEnvelope> envOpt = readEnvelopeFile(inflight);
                if (envOpt.isPresent()) {
                    OutboxEnvelope env = envOpt.get();
                    OutboxEnvelope updated = new OutboxEnvelope(
                            env.id(),
                            env.createdAtEpochMs(),
                            env.attempts() + 1,
                            now,
                            truncate(error, 400),
                            env.event());
                    // If max retries exceeded, quarantine instead of re-queueing.
                    if (shouldQuarantine(updated)) {
                        quarantineTotal.incrementAndGet();
                        writeEnvelopeFile(toQuarantinePath(inflight), updated);
                    } else {
                        writeEnvelopeFile(toPendingPath(inflight), updated);
                    }
                    safeDelete(inflight);
                } else {
                    // If unreadable, just release it
                    release(token);
                }
            } else {
                Optional<OutboxEnvelope> envOpt = removeAndReturnFromJsonlById(jsonlInflight, token);
                if (envOpt.isPresent()) {
                    OutboxEnvelope env = envOpt.get();
                    OutboxEnvelope updated = new OutboxEnvelope(
                            env.id(),
                            env.createdAtEpochMs(),
                            env.attempts() + 1,
                            now,
                            truncate(error, 400),
                            env.event());
                    // If max retries exceeded, quarantine instead of re-queueing.
                    if (shouldQuarantine(updated)) {
                        quarantineTotal.incrementAndGet();
                        appendJsonl(jsonlQuarantine, updated);
                    } else {
                        appendJsonl(jsonlPending, updated);
                    }
                }
            }
            nackTotal.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public OutboxStats stats() {
        lock.lock();
        try {
            if (mode == StorageMode.DIRECTORY) {
                return statsDirectory();
            }
            return statsJsonl();
        } finally {
            lock.unlock();
        }
    }

    // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_PEEK_V1
    @Override
    public List<OutboxPeekItem> peek(String state, int limit, int maxSnippetChars) {
        lock.lock();
        try {
            return peekInternal(state, limit, maxSnippetChars);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public OutboxSweepResult sweep() {
        lock.lock();
        try {
            return sweepInternal();
        } finally {
            lock.unlock();
        }
    }

    private List<OutboxPeekItem> peekInternal(String state, int limit, int maxSnippetChars) {
        int lim = Math.min(Math.max(limit, 0), 200);
        if (lim <= 0)
            return List.of();

        String s = state == null ? "pending" : state.trim().toLowerCase(Locale.ROOT);

        if (mode == StorageMode.DIRECTORY) {
            return peekDirectory(s, lim, maxSnippetChars);
        }
        return peekJsonl(s, lim, maxSnippetChars);
    }

    private List<OutboxPeekItem> peekDirectory(String state, int limit, int maxSnippetChars) {
        Set<String> states = normalizePeekStates(state);
        List<OutboxPeekItem> items = new ArrayList<>();

        if (states.contains("pending")) {
            items.addAll(peekDirectoryGlob(basePath, DIR_PENDING_GLOB, "pending", maxSnippetChars));
        }
        if (states.contains("inflight")) {
            items.addAll(peekDirectoryGlob(basePath, "*" + DIR_INFLIGHT_SUFFIX, "inflight", maxSnippetChars));
        }
        if (states.contains("quarantine")) {
            if (Files.isDirectory(quarantineDir)) {
                items.addAll(peekDirectoryGlob(quarantineDir, "*", "quarantine", maxSnippetChars));
            }
        }
        if (states.contains("bad")) {
            items.addAll(peekDirectoryGlob(basePath, "*.bad", "bad", maxSnippetChars));
            if (Files.isDirectory(quarantineDir)) {
                items.addAll(peekDirectoryGlob(quarantineDir, "*.bad", "bad", maxSnippetChars));
            }
        }

        items.sort(Comparator.comparing(OutboxPeekItem::createdAt).reversed());
        if (items.size() <= limit)
            return items;
        return items.subList(0, limit);
    }

    private List<OutboxPeekItem> peekDirectoryGlob(Path dir, String glob, String state, int maxSnippetChars) {
        if (dir == null || !Files.isDirectory(dir))
            return List.of();
        List<OutboxPeekItem> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p))
                    continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp"))
                    continue;

                Optional<OutboxEnvelope> envOpt = readEnvelopeFile(p);
                OutboxEnvelope env = envOpt.orElse(null);

                long createdMs = env != null ? env.createdAtEpochMs() : fileCreatedAtHint(p);
                Instant createdAt = Instant.ofEpochMilli(createdMs);
                Instant lastAttemptAt = (env != null && env.lastAttemptEpochMs() != null)
                        ? Instant.ofEpochMilli(env.lastAttemptEpochMs())
                        : null;

                PendingMemoryEvent ev = env == null ? null : truncateEvent(env.event(), maxSnippetChars);
                Map<String, Object> meta = new HashMap<>();
                meta.put("path", p.toString());
                meta.put("storageMode", "dir");
                meta.put("state", state);

                out.add(new OutboxPeekItem(
                        p.getFileName().toString(),
                        state,
                        env == null ? 0 : env.attempts(),
                        createdAt,
                        lastAttemptAt,
                        safeSize(p),
                        env == null ? null : env.lastError(),
                        ev,
                        meta));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private List<OutboxPeekItem> peekJsonl(String state, int limit, int maxSnippetChars) {
        Set<String> states = normalizePeekStates(state);
        List<OutboxPeekItem> items = new ArrayList<>();

        if (states.contains("pending")) {
            items.addAll(peekJsonlFile(jsonlPending, "pending", maxSnippetChars));
        }
        if (states.contains("inflight")) {
            items.addAll(peekJsonlFile(jsonlInflight, "inflight", maxSnippetChars));
        }
        if (states.contains("quarantine")) {
            items.addAll(peekJsonlFile(jsonlQuarantine, "quarantine", maxSnippetChars));
        }

        items.sort(Comparator.comparing(OutboxPeekItem::createdAt).reversed());
        if (items.size() <= limit)
            return items;
        return items.subList(0, limit);
    }

    private List<OutboxPeekItem> peekJsonlFile(Path file, String state, int maxSnippetChars) {
        if (file == null)
            return List.of();
        List<OutboxEnvelope> envs = readJsonlEnvelopes(file);
        if (envs.isEmpty())
            return List.of();
        List<OutboxPeekItem> out = new ArrayList<>(envs.size());
        for (OutboxEnvelope env : envs) {
            PendingMemoryEvent ev = truncateEvent(env.event(), maxSnippetChars);
            Map<String, Object> meta = Map.of(
                    "file", file.toString(),
                    "storageMode", "jsonl",
                    "state", state);
            out.add(new OutboxPeekItem(
                    env.id(),
                    state,
                    env.attempts(),
                    Instant.ofEpochMilli(env.createdAtEpochMs()),
                    env.lastAttemptEpochMs() == null ? null : Instant.ofEpochMilli(env.lastAttemptEpochMs()),
                    env.event() == null ? 0L : env.event().sizeBytes(),
                    env.lastError(),
                    ev,
                    meta));
        }
        return out;
    }

    private Set<String> normalizePeekStates(String rawState) {
        String s = rawState == null ? "pending" : rawState.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "all" -> Set.of("pending", "inflight", "quarantine", "bad");
            case "pending" -> Set.of("pending");
            case "inflight" -> Set.of("inflight");
            case "quarantine" -> Set.of("quarantine");
            case "bad" -> Set.of("bad");
            default -> Set.of("pending");
        };
    }

    private PendingMemoryEvent truncateEvent(PendingMemoryEvent ev, int maxSnippetChars) {
        if (ev == null)
            return null;
        int max = Math.max(0, maxSnippetChars);
        String snippet = ev.answerSnippet();
        if (snippet != null && max > 0 && snippet.length() > max) {
            snippet = snippet.substring(0, max);
        }
        return new PendingMemoryEvent(
                ev.sessionKey(),
                ev.contextKey(),
                ev.userQueryHash(),
                snippet,
                ev.occurredAt(),
                ev.sizeBytes());
    }

    // -------------------- Directory mode --------------------

    private void writeEnvelopeFile(OutboxEnvelope env) {
        try {
            Files.createDirectories(basePath);
            String fileName = "outbox_" + env.createdAtEpochMs() + "_" + env.id() + ".json";
            Path finalPath = basePath.resolve(fileName);
            writeEnvelopeFile(finalPath, env);
        } catch (Exception e) {
            // fail-soft
        }
    }

    private void writeEnvelopeFile(Path finalPath, OutboxEnvelope env) {
        try {
            Files.createDirectories(finalPath.getParent());
            Path tmp = finalPath.resolveSibling(finalPath.getFileName().toString() + ".tmp");
            String json = objectMapper.writeValueAsString(env);
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            safeMove(tmp, finalPath);
        } catch (Exception e) {
            // fail-soft
        }
    }

    private Optional<OutboxEnvelope> readEnvelopeFile(Path p) {
        try {
            if (p == null || !Files.exists(p))
                return Optional.empty();
            String json = Files.readString(p, StandardCharsets.UTF_8);
            return Optional.ofNullable(objectMapper.readValue(json, OutboxEnvelope.class));
        } catch (Exception e) {
            parseErrorTotal.incrementAndGet();
            return Optional.empty();
        }
    }

    private List<ClaimedPending> claimDirectory(int max) {
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath, DIR_PENDING_GLOB)) {
            for (Path p : stream) {
                candidates.add(p);
            }
        } catch (IOException e) {
            return List.of();
        }

        candidates.sort(Comparator.comparingLong(this::fileCreatedAtHint));

        List<ClaimedPending> claimed = new ArrayList<>();
        for (Path pending : candidates) {
            if (claimed.size() >= max)
                break;
            Path inflight = toInflightPath(pending);
            if (!safeMove(pending, inflight)) {
                continue;
            }

            Optional<OutboxEnvelope> envOpt = readEnvelopeFile(inflight);
            if (envOpt.isEmpty() || envOpt.get().event() == null) {
                // quarantine bad payload
                safeMove(inflight, inflight.resolveSibling(inflight.getFileName().toString() + ".bad"));
                parseErrorTotal.incrementAndGet();
                continue;
            }
            OutboxEnvelope env = envOpt.get();
            long size = safeSize(inflight);
            claimed.add(new ClaimedPending(
                    inflight.getFileName().toString(),
                    env.event(),
                    env.attempts(),
                    Instant.ofEpochMilli(env.createdAtEpochMs()),
                    env.lastAttemptEpochMs() == null ? null : Instant.ofEpochMilli(env.lastAttemptEpochMs()),
                    size,
                    Map.of("storage", "dir")));
        }

        return claimed;
    }

    private OutboxStats statsDirectory() {
        int pending = 0;
        int inflight = 0;
        long bytes = 0L;
        Long oldest = null;
        Long newest = null;

        try {
            Files.createDirectories(basePath);
        } catch (IOException ignored) {
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p))
                    continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp"))
                    continue;

                boolean isPending = name.endsWith(".json");
                boolean isInflight = name.endsWith(".inflight");
                boolean isBad = name.endsWith(".bad");
                if (!isPending && !isInflight && !isBad)
                    continue;

                if (isPending)
                    pending++;
                else if (isInflight)
                    inflight++;

                long sz = safeSize(p);
                bytes += sz;

                long ts = fileCreatedAtHint(p);
                oldest = (oldest == null ? ts : Math.min(oldest, ts));
                newest = (newest == null ? ts : Math.max(newest, ts));
            }
        } catch (IOException ignored) {
        }

        // Include quarantine folder in "total bytes" and oldest/newest hints (but do
        // not
        // treat quarantined items as pending/inflight).
        if (quarantineEnabled) {
            try {
                Files.createDirectories(quarantineDir);
            } catch (IOException ignored) {
            }
            try (DirectoryStream<Path> qStream = Files.newDirectoryStream(quarantineDir)) {
                for (Path p : qStream) {
                    if (!Files.isRegularFile(p))
                        continue;
                    String name = p.getFileName().toString();
                    if (name.endsWith(".tmp"))
                        continue;

                    boolean isJson = name.endsWith(".json") || name.endsWith(".bad");
                    if (!isJson)
                        continue;

                    long sz = safeSize(p);
                    bytes += sz;

                    long ts = fileCreatedAtHint(p);
                    oldest = (oldest == null ? ts : Math.min(oldest, ts));
                    newest = (newest == null ? ts : Math.max(newest, ts));
                }
            } catch (IOException ignored) {
            }
        }

        return new OutboxStats(
                true,
                "dir",
                basePath.toString(),
                pending,
                inflight,
                bytes,
                oldest == null ? null : Instant.ofEpochMilli(oldest),
                newest == null ? null : Instant.ofEpochMilli(newest),
                maxFiles,
                maxBytes,
                ttlSeconds,
                inflightStaleSeconds,
                ackTotal.get(),
                nackTotal.get(),
                releaseTotal.get(),
                droppedExpiredTotal.get(),
                droppedByLimitTotal.get(),
                parseErrorTotal.get(),
                lastSweepEpochMs.get());
    }

    private long fileCreatedAtHint(Path p) {
        // Name: outbox_<epochMs>_<id>.(json|inflight)
        try {
            String name = p.getFileName().toString();
            int us = name.indexOf('_');
            int us2 = name.indexOf('_', us + 1);
            if (us >= 0 && us2 > us) {
                String ts = name.substring(us + 1, us2);
                return Long.parseLong(ts);
            }
        } catch (Exception ignored) {
        }
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ignored) {
        }
        return System.currentTimeMillis();
    }

    private Path toInflightPath(Path pending) {
        if (pending == null)
            return null;
        String name = pending.getFileName().toString();
        if (name.endsWith(".json")) {
            String base = name.substring(0, name.length() - ".json".length());
            return pending.resolveSibling(base + ".inflight");
        }
        return pending.resolveSibling(name + ".inflight");
    }

    private Path toPendingPath(Path inflight) {
        if (inflight == null)
            return null;
        String name = inflight.getFileName().toString();
        if (name.endsWith(".inflight")) {
            String base = name.substring(0, name.length() - ".inflight".length());
            return inflight.resolveSibling(base + ".json");
        }
        // fallback
        return inflight.resolveSibling(name + ".json");
    }

    // MERGE_HOOK:PROJ_AGENT::DEGRADED_OUTBOX_QUARANTINE_HELPERS_V1
    private boolean shouldQuarantine(OutboxEnvelope updated) {
        return quarantineEnabled && updated != null && updated.attempts() >= maxAttempts;
    }

    private Path toQuarantinePath(Path inflightOrPending) {
        // Directory-mode quarantine is stored under a dedicated subdirectory.
        Path pendingLike = inflightOrPending;
        String name = inflightOrPending.getFileName().toString();
        if (name.endsWith(DIR_INFLIGHT_SUFFIX)) {
            pendingLike = toPendingPath(inflightOrPending);
        }
        return quarantineDir.resolve(pendingLike.getFileName().toString());
    }

    // -------------------- JSONL mode --------------------

    private List<ClaimedPending> claimJsonl(int max) {
        // Read inflight ids to avoid duplicate claim
        Set<String> inflightIds = new HashSet<>();
        for (OutboxEnvelope env : readJsonlEnvelopes(jsonlInflight)) {
            if (env != null && env.id() != null)
                inflightIds.add(env.id());
        }

        List<OutboxEnvelope> pending = readJsonlEnvelopes(jsonlPending);
        long now = System.currentTimeMillis();

        List<OutboxEnvelope> toClaim = new ArrayList<>();
        List<OutboxEnvelope> keep = new ArrayList<>();

        for (OutboxEnvelope env : pending) {
            if (env == null || env.event == null)
                continue;
            if (isExpired(env, now)) {
                droppedExpiredTotal.incrementAndGet();
                continue;
            }
            if (env.id != null && inflightIds.contains(env.id)) {
                // drop duplicates while inflight exists
                continue;
            }
            if (toClaim.size() < max) {
                toClaim.add(env);
            } else {
                keep.add(env);
            }
        }

        // Order of operations: append to inflight first (prefer duplicates over loss)
        for (OutboxEnvelope env : toClaim) {
            appendJsonl(jsonlInflight, env);
        }
        writeJsonl(jsonlPending, keep);

        List<ClaimedPending> claimed = new ArrayList<>();
        for (OutboxEnvelope env : toClaim) {
            claimed.add(new ClaimedPending(
                    env.id,
                    env.event,
                    env.attempts,
                    Instant.ofEpochMilli(env.createdAtEpochMs),
                    env.lastAttemptEpochMs == null ? null : Instant.ofEpochMilli(env.lastAttemptEpochMs),
                    -1L,
                    Map.of("storage", "jsonl")));
        }
        return claimed;
    }

    private OutboxStats statsJsonl() {
        List<OutboxEnvelope> pending = readJsonlEnvelopes(jsonlPending);
        List<OutboxEnvelope> inflight = readJsonlEnvelopes(jsonlInflight);
        List<OutboxEnvelope> quarantine = readJsonlEnvelopes(jsonlQuarantine);
        int pendingCount = pending.size();
        int inflightCount = inflight.size();
        long bytes = safeSize(jsonlPending) + safeSize(jsonlInflight) + safeSize(jsonlQuarantine);

        Long oldest = null;
        Long newest = null;
        for (OutboxEnvelope env : pending) {
            if (env == null)
                continue;
            oldest = (oldest == null ? env.createdAtEpochMs : Math.min(oldest, env.createdAtEpochMs));
            newest = (newest == null ? env.createdAtEpochMs : Math.max(newest, env.createdAtEpochMs));
        }
        for (OutboxEnvelope env : inflight) {
            if (env == null)
                continue;
            oldest = (oldest == null ? env.createdAtEpochMs : Math.min(oldest, env.createdAtEpochMs));
            newest = (newest == null ? env.createdAtEpochMs : Math.max(newest, env.createdAtEpochMs));
        }

        for (OutboxEnvelope env : quarantine) {
            if (env == null)
                continue;
            oldest = (oldest == null ? env.createdAtEpochMs : Math.min(oldest, env.createdAtEpochMs));
            newest = (newest == null ? env.createdAtEpochMs : Math.max(newest, env.createdAtEpochMs));
        }

        return new OutboxStats(
                true,
                "jsonl",
                jsonlPending.toString(),
                pendingCount,
                inflightCount,
                bytes,
                oldest == null ? null : Instant.ofEpochMilli(oldest),
                newest == null ? null : Instant.ofEpochMilli(newest),
                maxFiles,
                maxBytes,
                ttlSeconds,
                inflightStaleSeconds,
                ackTotal.get(),
                nackTotal.get(),
                releaseTotal.get(),
                droppedExpiredTotal.get(),
                droppedByLimitTotal.get(),
                parseErrorTotal.get(),
                lastSweepEpochMs.get());
    }

    private List<OutboxEnvelope> readJsonlEnvelopes(Path file) {
        List<OutboxEnvelope> out = new ArrayList<>();
        if (file == null || !Files.exists(file))
            return out;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;
                OutboxEnvelope env = parseEnvelopeOrEvent(line);
                if (env != null && env.event != null) {
                    out.add(env);
                }
            }
        } catch (IOException e) {
            // fail-soft
        }
        return out;
    }

    private void writeJsonl(Path file, List<OutboxEnvelope> envs) {
        if (file == null)
            return;
        try {
            if (file.getParent() != null)
                Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                for (OutboxEnvelope env : envs) {
                    if (env == null || env.event == null)
                        continue;
                    w.write(objectMapper.writeValueAsString(env));
                    w.newLine();
                }
            }
            safeMove(tmp, file);
        } catch (IOException e) {
            // fail-soft
        }
    }

    private void appendJsonl(Path file, OutboxEnvelope env) {
        if (file == null || env == null || env.event == null)
            return;
        try {
            if (file.getParent() != null)
                Files.createDirectories(file.getParent());
            Files.writeString(file,
                    objectMapper.writeValueAsString(env) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // fail-soft
        }
    }

    private OutboxEnvelope parseEnvelopeOrEvent(String jsonLine) {
        try {
            OutboxEnvelope env = objectMapper.readValue(jsonLine, OutboxEnvelope.class);
            if (env != null && env.event != null && env.id != null) {
                return env;
            }
        } catch (Exception ignored) {
        }

        // Legacy: plain PendingMemoryEvent per line
        try {
            PendingMemoryEvent evt = objectMapper.readValue(jsonLine, PendingMemoryEvent.class);
            if (evt == null)
                return null;
            long ts = evt.timestamp() != null ? evt.timestamp().toEpochMilli() : System.currentTimeMillis();
            return new OutboxEnvelope(newId(), ts, 0, null, null, evt);
        } catch (Exception e) {
            parseErrorTotal.incrementAndGet();
            return null;
        }
    }

    private void removeFromJsonlById(Path file, String id) {
        if (file == null || id == null)
            return;
        List<OutboxEnvelope> envs = readJsonlEnvelopes(file);
        if (envs.isEmpty())
            return;
        List<OutboxEnvelope> keep = new ArrayList<>();
        for (OutboxEnvelope env : envs) {
            if (env != null && id.equals(env.id))
                continue;
            keep.add(env);
        }
        writeJsonl(file, keep);
    }

    private Optional<OutboxEnvelope> removeAndReturnFromJsonlById(Path file, String id) {
        if (file == null || id == null)
            return Optional.empty();
        List<OutboxEnvelope> envs = readJsonlEnvelopes(file);
        if (envs.isEmpty())
            return Optional.empty();
        List<OutboxEnvelope> keep = new ArrayList<>();
        OutboxEnvelope found = null;
        for (OutboxEnvelope env : envs) {
            if (env != null && id.equals(env.id)) {
                found = env;
                continue;
            }
            keep.add(env);
        }
        writeJsonl(file, keep);
        return Optional.ofNullable(found);
    }

    // -------------------- Sweep / retention --------------------

    private OutboxSweepResult sweepInternal() {
        long now = System.currentTimeMillis();
        long bytesBefore = totalBytesInternal();

        int removedExpired = 0;
        int removedByMaxFiles = 0;
        int removedByMaxBytes = 0;
        int recoveredInflight = 0;

        if (mode == StorageMode.DIRECTORY) {
            // Recover stale inflight first
            recoveredInflight += recoverStaleInflightDirectory(now);
            removedExpired += removeExpiredDirectory(now);
            removedByMaxFiles += enforceMaxFilesDirectory();
            removedByMaxBytes += enforceMaxBytesDirectory();
        } else {
            recoveredInflight += recoverStaleInflightJsonl(now);
            removedExpired += removeExpiredJsonl(now);
            removedByMaxFiles += enforceMaxFilesJsonl();
            removedByMaxBytes += enforceMaxBytesJsonl();
        }

        long bytesAfter = totalBytesInternal();
        lastSweepEpochMs.set(now);
        return new OutboxSweepResult(now, removedExpired, removedByMaxFiles, removedByMaxBytes, recoveredInflight,
                bytesBefore, bytesAfter);
    }

    private long totalBytesInternal() {
        if (mode == StorageMode.DIRECTORY) {
            long bytes = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath)) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p))
                        bytes += safeSize(p);
                }
            } catch (IOException ignored) {
            }
            if (Files.isDirectory(quarantineDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantineDir)) {
                    for (Path p : stream) {
                        if (Files.isRegularFile(p))
                            bytes += safeSize(p);
                    }
                } catch (IOException ignored) {
                }
            }
            return bytes;
        }
        return safeSize(jsonlPending) + safeSize(jsonlInflight) + safeSize(jsonlQuarantine);
    }

    private boolean isExpired(OutboxEnvelope env, long nowEpochMs) {
        if (ttlSeconds <= 0)
            return false;
        long ageMs = nowEpochMs - env.createdAtEpochMs;
        return ageMs > ttlSeconds * 1000L;
    }

    // ----- directory sweep helpers -----

    private int recoverStaleInflightDirectory(long now) {
        if (inflightStaleSeconds <= 0)
            return 0;
        int recovered = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath, DIR_INFLIGHT_GLOB)) {
            for (Path inflight : stream) {
                long mtime = safeMtime(inflight);
                if (now - mtime > inflightStaleSeconds * 1000L) {
                    if (safeMove(inflight, toPendingPath(inflight))) {
                        recovered++;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return recovered;
    }

    private int removeExpiredDirectory(long now) {
        if (ttlSeconds <= 0)
            return 0;
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p))
                    continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp"))
                    continue;
                // NOTE: include .bad here for disk protection.
                if (!(name.endsWith(".json") || name.endsWith(".inflight") || name.endsWith(".bad")))
                    continue;
                long ts = fileCreatedAtHint(p);
                if (now - ts > ttlSeconds * 1000L) {
                    if (safeDelete(p)) {
                        removed++;
                        droppedExpiredTotal.incrementAndGet();
                    }
                }
            }
        } catch (IOException ignored) {
        }

        // also apply TTL to quarantine folder entries
        if (Files.isDirectory(quarantineDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantineDir)) {
                for (Path p : stream) {
                    if (!Files.isRegularFile(p))
                        continue;
                    String name = p.getFileName().toString();
                    if (name.endsWith(".tmp"))
                        continue;
                    if (!(name.endsWith(".json") || name.endsWith(".inflight") || name.endsWith(".bad")))
                        continue;
                    long ts = fileCreatedAtHint(p);
                    if (now - ts > ttlSeconds * 1000L) {
                        if (safeDelete(p)) {
                            removed++;
                            droppedExpiredTotal.incrementAndGet();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return removed;
    }

    private int enforceMaxFilesDirectory() {
        if (maxFiles <= 0)
            return 0;
        List<Path> nonInflight = new ArrayList<>();
        List<Path> inflight = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p))
                    continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp"))
                    continue;
                if (name.endsWith(".inflight")) {
                    inflight.add(p);
                } else if (name.endsWith(".json") || name.endsWith(".bad")) {
                    nonInflight.add(p);
                }
            }
        } catch (IOException ignored) {
        }

        if (Files.isDirectory(quarantineDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantineDir)) {
                for (Path p : stream) {
                    if (!Files.isRegularFile(p))
                        continue;
                    String name = p.getFileName().toString();
                    if (name.endsWith(".tmp"))
                        continue;
                    if (name.endsWith(".inflight")) {
                        inflight.add(p);
                    } else if (name.endsWith(".json") || name.endsWith(".bad")) {
                        nonInflight.add(p);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        long total = (long) nonInflight.size() + (long) inflight.size();
        if (total <= maxFiles)
            return 0;

        int removed = 0;
        long need = total - maxFiles;

        nonInflight.sort(Comparator.comparingLong(this::fileCreatedAtHint));
        for (Path p : nonInflight) {
            if (need <= 0)
                break;
            if (safeDelete(p)) {
                removed++;
                need--;
                droppedByLimitTotal.incrementAndGet();
            }
        }

        inflight.sort(Comparator.comparingLong(this::fileCreatedAtHint));
        for (Path p : inflight) {
            if (need <= 0)
                break;
            if (safeDelete(p)) {
                removed++;
                need--;
                droppedByLimitTotal.incrementAndGet();
            }
        }
        return removed;
    }

    private int enforceMaxBytesDirectory() {
        if (maxBytes <= 0)
            return 0;
        long bytes = totalBytesInternal();
        if (bytes <= maxBytes)
            return 0;

        // Prefer deleting non-inflight artifacts first (pending, quarantine, .bad)
        // before touching
        // actively processed inflight files.
        List<Path> nonInflight = new ArrayList<>();
        List<Path> inflight = new ArrayList<>();

        collectDirectoryCandidates(basePath, nonInflight, inflight);
        if (Files.isDirectory(quarantineDir)) {
            collectDirectoryCandidates(quarantineDir, nonInflight, inflight);
        }

        nonInflight.sort(Comparator.comparingLong(this::fileCreatedAtHint));
        inflight.sort(Comparator.comparingLong(this::fileCreatedAtHint));

        int removed = 0;
        for (Path p : nonInflight) {
            if (bytes <= maxBytes)
                break;
            long sz = safeSize(p);
            if (safeDelete(p)) {
                removed++;
                bytes = Math.max(0, bytes - sz);
                droppedByLimitTotal.incrementAndGet();
            }
        }
        for (Path p : inflight) {
            if (bytes <= maxBytes)
                break;
            long sz = safeSize(p);
            if (safeDelete(p)) {
                removed++;
                bytes = Math.max(0, bytes - sz);
                droppedByLimitTotal.incrementAndGet();
            }
        }
        return removed;
    }

    private void collectDirectoryCandidates(Path dir, List<Path> nonInflight, List<Path> inflight) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p))
                    continue;
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp"))
                    continue;
                if (name.endsWith(DIR_INFLIGHT_SUFFIX)) {
                    inflight.add(p);
                } else if (name.endsWith(".json") || name.endsWith(".bad")) {
                    nonInflight.add(p);
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ----- jsonl sweep helpers -----

    private int recoverStaleInflightJsonl(long now) {
        if (inflightStaleSeconds <= 0)
            return 0;
        if (!Files.exists(jsonlInflight))
            return 0;
        long mtime = safeMtime(jsonlInflight);
        if (now - mtime <= inflightStaleSeconds * 1000L)
            return 0;

        // Move all inflight back to pending (release semantics, no attempt increment)
        List<OutboxEnvelope> inflight = readJsonlEnvelopes(jsonlInflight);
        if (inflight.isEmpty())
            return 0;

        for (OutboxEnvelope env : inflight) {
            appendJsonl(jsonlPending, env);
        }
        writeJsonl(jsonlInflight, List.of());
        return inflight.size();
    }

    private int removeExpiredJsonl(long now) {
        if (ttlSeconds <= 0)
            return 0;
        int removed = 0;

        List<OutboxEnvelope> pending = readJsonlEnvelopes(jsonlPending);
        List<OutboxEnvelope> inflight = readJsonlEnvelopes(jsonlInflight);
        List<OutboxEnvelope> quarantine = readJsonlEnvelopes(jsonlQuarantine);

        List<OutboxEnvelope> pendingKeep = new ArrayList<>();
        for (OutboxEnvelope env : pending) {
            if (env == null || env.event == null)
                continue;
            if (isExpired(env, now)) {
                removed++;
                droppedExpiredTotal.incrementAndGet();
                continue;
            }
            pendingKeep.add(env);
        }
        List<OutboxEnvelope> inflightKeep = new ArrayList<>();
        for (OutboxEnvelope env : inflight) {
            if (env == null || env.event == null)
                continue;
            if (isExpired(env, now)) {
                removed++;
                droppedExpiredTotal.incrementAndGet();
                continue;
            }
            inflightKeep.add(env);
        }
        writeJsonl(jsonlPending, pendingKeep);
        writeJsonl(jsonlInflight, inflightKeep);

        // quarantine is not retried, but should still be subject to TTL for disk
        // protection
        List<OutboxEnvelope> quarantineKeep = new ArrayList<>();
        for (OutboxEnvelope env : quarantine) {
            if (env == null || env.event == null)
                continue;
            if (isExpired(env, now)) {
                removed++;
                droppedExpiredTotal.incrementAndGet();
                continue;
            }
            quarantineKeep.add(env);
        }
        writeJsonl(jsonlQuarantine, quarantineKeep);

        return removed;
    }

    private int enforceMaxFilesJsonl() {
        if (maxFiles <= 0)
            return 0;
        List<OutboxEnvelope> pending = readJsonlEnvelopes(jsonlPending);
        List<OutboxEnvelope> inflight = readJsonlEnvelopes(jsonlInflight);
        List<OutboxEnvelope> quarantine = readJsonlEnvelopes(jsonlQuarantine);
        int total = pending.size() + inflight.size() + quarantine.size();
        if (total <= maxFiles)
            return 0;

        // Drop oldest from PENDING first, then QUARANTINE. Avoid dropping inflight.
        pending.sort(Comparator.comparingLong(e -> e.createdAtEpochMs));
        quarantine.sort(Comparator.comparingLong(e -> e.createdAtEpochMs));

        int dropped = 0;
        int droppedPending = 0;
        int droppedQuarantine = 0;

        while (total > maxFiles && !pending.isEmpty()) {
            pending.remove(0);
            dropped++;
            droppedPending++;
            total--;
            droppedByLimitTotal.incrementAndGet();
        }

        while (total > maxFiles && !quarantine.isEmpty()) {
            quarantine.remove(0);
            dropped++;
            droppedQuarantine++;
            total--;
            droppedByLimitTotal.incrementAndGet();
        }

        if (droppedPending > 0)
            writeJsonl(jsonlPending, pending);
        if (droppedQuarantine > 0)
            writeJsonl(jsonlQuarantine, quarantine);
        return dropped;
    }

    private int enforceMaxBytesJsonl() {
        if (maxBytes <= 0)
            return 0;
        long bytes = totalBytesInternal();
        if (bytes <= maxBytes)
            return 0;

        List<OutboxEnvelope> pending = readJsonlEnvelopes(jsonlPending);
        List<OutboxEnvelope> quarantine = readJsonlEnvelopes(jsonlQuarantine);

        pending.sort(Comparator.comparingLong(e -> e.createdAtEpochMs));
        quarantine.sort(Comparator.comparingLong(e -> e.createdAtEpochMs));

        int removed = 0;

        // 1) Drop oldest pending items first
        List<OutboxEnvelope> pendingKeep = new ArrayList<>(pending);
        boolean pendingTouched = false;
        while (bytes > maxBytes && !pendingKeep.isEmpty()) {
            writeJsonl(jsonlPending, pendingKeep);
            bytes = totalBytesInternal();
            if (bytes <= maxBytes)
                break;

            pendingKeep.remove(0);
            pendingTouched = true;
            removed++;
            droppedByLimitTotal.incrementAndGet();
        }
        if (pendingTouched) {
            writeJsonl(jsonlPending, pendingKeep);
            bytes = totalBytesInternal();
        }

        // 2) If still over, drop oldest quarantined items
        List<OutboxEnvelope> quarantineKeep = new ArrayList<>(quarantine);
        boolean quarantineTouched = false;
        while (bytes > maxBytes && !quarantineKeep.isEmpty()) {
            writeJsonl(jsonlQuarantine, quarantineKeep);
            bytes = totalBytesInternal();
            if (bytes <= maxBytes)
                break;

            quarantineKeep.remove(0);
            quarantineTouched = true;
            removed++;
            droppedByLimitTotal.incrementAndGet();
        }
        if (quarantineTouched) {
            writeJsonl(jsonlQuarantine, quarantineKeep);
        }

        return removed;
    }

    // -------------------- Utilities --------------------

    private static String newId() {
        // shorter than UUID while still reasonably unique
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return null;
        if (max <= 0)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max);
    }

    private static long safeMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static long safeSize(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean safeDelete(Path p) {
        try {
            if (p == null)
                return false;
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean safeMove(Path from, Path to) {
        if (from == null || to == null)
            return false;
        try {
            if (to.getParent() != null)
                Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
