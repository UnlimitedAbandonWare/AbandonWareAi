package com.example.lms.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** JSONL receipt store that atomically rewrites a forced, hash-only ledger. */
@Component
public final class JsonlLifecycleReceiptStore implements DurableLifecycleReceiptStore {

    private static final ConcurrentHashMap<Path, ReentrantLock> PATH_LOCKS = new ConcurrentHashMap<>();
    private static final String HASH_PATTERN = "[0-9a-f]{64}";

    private final Path path;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock;

    @Autowired
    public JsonlLifecycleReceiptStore(
            ObjectMapper objectMapper,
            @Value("${lifecycle.receipts.path:data/lifecycle-receipts.jsonl}") String configuredPath) {
        this(Path.of(configuredPath), objectMapper);
    }

    public JsonlLifecycleReceiptStore(Path path, ObjectMapper objectMapper) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.lock = PATH_LOCKS.computeIfAbsent(this.path, ignored -> new ReentrantLock());
    }

    @Override
    public Optional<Receipt> find(Lifecycle lifecycle, String subjectHash) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        requireHash(subjectHash, "subjectHash");
        lock.lock();
        try {
            List<Receipt> receipts = readAll();
            for (int index = receipts.size() - 1; index >= 0; index--) {
                Receipt receipt = receipts.get(index);
                if (receipt.lifecycle() == lifecycle && receipt.subjectHash().equals(subjectHash)) {
                    return Optional.of(receipt);
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Receipt> latest(Lifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        lock.lock();
        try {
            List<Receipt> receipts = readAll();
            for (int index = receipts.size() - 1; index >= 0; index--) {
                Receipt receipt = receipts.get(index);
                if (receipt.lifecycle() == lifecycle) {
                    return Optional.of(receipt);
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void record(Receipt receipt) {
        validate(receipt);
        lock.lock();
        try {
            List<Receipt> receipts = readAll();
            receipts.add(receipt);
            writeAll(receipts);
        } finally {
            lock.unlock();
        }
    }

    private List<Receipt> readAll() {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("lifecycle_receipt_path_not_file");
        }
        List<Receipt> receipts = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Receipt receipt = objectMapper.readValue(line, Receipt.class);
                validate(receipt);
                receipts.add(receipt);
            }
            return receipts;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("lifecycle_receipt_read_failed", failure);
        }
    }

    private void writeAll(List<Receipt> receipts) {
        Path parent = path.getParent();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] content = serialize(receipts).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveIntoPlace(temporary);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw new IllegalStateException("lifecycle_receipt_write_failed", failure);
        }
    }

    private String serialize(List<Receipt> receipts) throws JsonProcessingException {
        StringBuilder output = new StringBuilder();
        for (Receipt receipt : receipts) {
            validate(receipt);
            output.append(objectMapper.writeValueAsString(receipt)).append(System.lineSeparator());
        }
        return output.toString();
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validate(Receipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("receipt");
        }
        if (receipt.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion");
        }
        Objects.requireNonNull(receipt.lifecycle(), "lifecycle");
        Objects.requireNonNull(receipt.state(), "state");
        Objects.requireNonNull(receipt.reason(), "reason");
        requireHash(receipt.subjectHash(), "subjectHash");
        requireHash(receipt.payloadHash(), "payloadHash");
        if (receipt.atEpochMs() < 0L) {
            throw new IllegalArgumentException("atEpochMs");
        }
        if (!allowedState(receipt.lifecycle(), receipt.state())) {
            throw new IllegalArgumentException("lifecycleState");
        }
    }

    private static boolean allowedState(Lifecycle lifecycle, State state) {
        return switch (lifecycle) {
            case FEEDBACK -> state == State.PENDING || state == State.COMMITTED || state == State.FAILED;
            case N8N -> state == State.INTENT || state == State.ACCEPTED || state == State.FAILED;
            case ATTACHMENT -> state == State.CREATED
                    || state == State.DELETE_REQUESTED
                    || state == State.DELETED
                    || state == State.DELETE_FAILED;
        };
    }

    private static void requireHash(String value, String name) {
        if (value == null || !value.matches(HASH_PATTERN)) {
            throw new IllegalArgumentException(name);
        }
    }
}
