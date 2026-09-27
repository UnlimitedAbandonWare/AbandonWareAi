package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CfvmSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(CfvmSnapshotService.class);

    private final ObjectMapper mapper;
    private final ObjectProvider<RawMatrixBuffer> bufferProvider;
    private final ObjectProvider<CfvmSnapshotRepository> repositoryProvider;

    @Autowired
    public CfvmSnapshotService(ObjectProvider<RawMatrixBuffer> bufferProvider,
                               ObjectProvider<CfvmSnapshotRepository> repositoryProvider) {
        this(new ObjectMapper(), bufferProvider, repositoryProvider);
    }

    CfvmSnapshotService(ObjectMapper mapper,
                        ObjectProvider<RawMatrixBuffer> bufferProvider,
                        ObjectProvider<CfvmSnapshotRepository> repositoryProvider) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.bufferProvider = bufferProvider;
        this.repositoryProvider = repositoryProvider;
    }

    @PostConstruct
    public void restoreOnStartup() {
        TraceStore.put("cfvm.snapshot.restored", false);
        TraceStore.put("cfvm.snapshot.restored.id", null);
        TraceStore.put("cfvm.snapshot.restore.skipped", null);
        TraceStore.put("cfvm.snapshot.restore.error", null);
        RawMatrixBuffer buffer = bufferProvider == null ? null : bufferProvider.getIfAvailable();
        CfvmSnapshotRepository repository = repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
        if (buffer == null || repository == null) {
            TraceStore.put("cfvm.snapshot.restore.skipped", "bean_unavailable");
            return;
        }
        try {
            Optional<CfvmSnapshot> latest = repository.findTopByOrderByCreatedAtDesc();
            if (latest.isEmpty()) {
                TraceStore.put("cfvm.snapshot.restore.skipped", "no_previous_snapshot");
                return;
            }
            CfvmSnapshot snapshot = latest.get();
            double[] weights = mapper.readValue(snapshot.getWeightsJson(), double[].class);
            buffer.restoreFromSnapshot(weights, snapshot.getBoltzmannTemp());
            // The legacy void restore method skips incompatible shapes without throwing.
            if (weights == null || weights.length != buffer.exportWeights().length) {
                TraceStore.put("cfvm.snapshot.restore.skipped", "weight_count_mismatch");
                return;
            }
            TraceStore.put("cfvm.snapshot.restored", true);
            TraceStore.put("cfvm.snapshot.restored.id", snapshot.getId());
            log.info("[AWX][cfvm] snapshot restored idPresent={}", snapshot.getId() != null);
        } catch (JsonProcessingException e) {
            TraceStore.put("cfvm.snapshot.restore.error", "json_parse");
            log.warn("[AWX][cfvm] snapshot restore failed reason=json_parse");
        } catch (RuntimeException e) {
            TraceStore.put("cfvm.snapshot.restore.error", e.getClass().getSimpleName());
            log.warn("[AWX][cfvm] snapshot restore failed type={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${cfvm.snapshot.fixed-delay-ms:60000}",
            initialDelayString = "${cfvm.snapshot.initial-delay-ms:30000}")
    public void periodicSnapshot() {
        persistSnapshot(null);
    }

    public void persistSnapshot(String sessionId) {
        RawMatrixBuffer buffer = bufferProvider == null ? null : bufferProvider.getIfAvailable();
        CfvmSnapshotRepository repository = repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
        if (buffer == null || repository == null) {
            TraceStore.put("cfvm.snapshot.save.skipped", "buffer_or_repo_unavailable");
            return;
        }
        try {
            double[] weights = buffer.exportWeights();
            CfvmSnapshot snapshot = new CfvmSnapshot();
            snapshot.setWeightsJson(mapper.writeValueAsString(weights));
            snapshot.setBoltzmannTemp(buffer.getBoltzmannTemp());
            snapshot.setBufferSize(weights.length);
            snapshot.setDominantSlot(dominantSlot(weights));
            snapshot.setSessionHash(SafeRedactor.hashValue(sessionId));
            repository.save(snapshot);
            TraceStore.put("cfvm.snapshot.saved", true);
            TraceStore.put("cfvm.snapshot.dominantSlot", snapshot.getDominantSlot());
            TraceStore.put("cfvm.snapshot.vectorWrite.enabled", false);
            TraceStore.put("cfvm.snapshot.vectorWrite.skipped", "jpa_snapshot_only");
        } catch (JsonProcessingException e) {
            TraceStore.put("cfvm.snapshot.save.error", "json_write");
            log.warn("[AWX][cfvm] snapshot save failed reason=json_write");
        } catch (RuntimeException e) {
            TraceStore.put("cfvm.snapshot.save.error", e.getClass().getSimpleName());
            log.debug("[AWX][cfvm] snapshot save failed type={}", e.getClass().getSimpleName());
        }
    }

    private static int dominantSlot(double[] weights) {
        if (weights == null || weights.length == 0) {
            return -1;
        }
        int slot = 0;
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > weights[slot]) {
                slot = i;
            }
        }
        return slot;
    }
}
