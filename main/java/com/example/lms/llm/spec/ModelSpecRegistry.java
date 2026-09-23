package com.example.lms.llm.spec;

import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelSpecRegistry {

    private static final System.Logger LOG = System.getLogger(ModelSpecRegistry.class.getName());

    private final ObjectMapper objectMapper;
    private final LlmGatewayProperties properties;
    private final ConcurrentHashMap<String, ModelSpecSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Object persistenceLock = new Object();

    public ModelSpecRegistry(ObjectMapper objectMapper, LlmGatewayProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(ModelSpecSnapshot snapshot) {
        if (snapshot == null || !enabled()) {
            return;
        }
        String key = snapshot.key();
        if (!StringUtils.hasText(key)) {
            return;
        }
        snapshots.put(key, snapshot);
        try {
            TraceStore.put("llm.gateway.spec.provider", snapshot.provider());
            TraceStore.put("llm.gateway.spec.modelHash", SafeRedactor.hashValue(snapshot.model()));
            TraceStore.put("llm.gateway.spec.modelLength", snapshot.model() == null ? 0 : snapshot.model().length());
            TraceStore.put("llm.gateway.spec.endpointHost", snapshot.endpointHost());
            TraceStore.put("llm.gateway.spec.contextTokens", snapshot.contextTokens());
            TraceStore.put("llm.gateway.spec.embeddingDim", snapshot.embeddingDim());
        } catch (Exception traceError) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Model spec registry telemetry skipped stage=publish_trace errorType=" + errorType(traceError));
        }
        persistBestEffort();
    }

    public Optional<ModelSpecSnapshot> snapshot(String provider, String model) {
        String key = ModelSpecSnapshot.key(provider, model);
        if (!StringUtils.hasText(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(key));
    }

    public Collection<ModelSpecSnapshot> snapshots() {
        return snapshots.values();
    }

    public Map<String, ModelSpecSnapshot> snapshotMap() {
        return new LinkedHashMap<>(snapshots);
    }

    private boolean enabled() {
        return properties == null || properties.getSpecRegistry().isEnabled();
    }

    private void persistBestEffort() {
        if (objectMapper == null || properties == null || !StringUtils.hasText(properties.getSpecRegistry().getPath())) {
            return;
        }
        synchronized (persistenceLock) {
            Path temporary = null;
            try {
                Path path = Path.of(properties.getSpecRegistry().getPath()).toAbsolutePath().normalize();
                Path parent = path.getParent();
                if (parent == null) {
                    throw new IOException("model-spec-parent-unavailable");
                }
                Files.createDirectories(parent);
                temporary = Files.createTempFile(parent, temporaryPrefix(path), ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshotMap());
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                moveIntoPlace(temporary, path);
                temporary = null;
            } catch (Exception ex) {
                try {
                    TraceStore.put("llm.gateway.spec.persistFailure", "llm_gateway_spec_persist_failed");
                } catch (Exception traceError) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "Model spec registry telemetry skipped stage=persist_failure_trace errorType=" + errorType(traceError));
                }
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (Exception cleanupError) {
                        LOG.log(System.Logger.Level.DEBUG,
                                "Model spec registry cleanup skipped stage=persist_temp_cleanup errorType="
                                        + errorType(cleanupError));
                    }
                }
            }
        }
    }

    private static String temporaryPrefix(Path target) {
        Path fileName = target.getFileName();
        String prefix = "." + (fileName == null ? "model-spec" : fileName) + ".";
        return prefix.length() >= 3 ? prefix : "spec.";
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void traceSpecTelemetrySkipped(String stage, Exception error) {
        LOG.log(System.Logger.Level.DEBUG,
                "Model spec registry telemetry skipped stage=" + stage + " errorType=" + errorType(error));
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
