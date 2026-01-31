package ai.abandonware.nova.orch.storage;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Micrometer metrics for degraded outbox storage.
 *
 * <p>Metrics are cached via a scheduled refresh to avoid scanning the underlying filesystem on
 * each scrape.
 */
public class OutboxMicrometerMetrics implements MeterBinder {

    private final ObjectProvider<DegradedStorage> storageProvider;
    private final NovaOrchestrationProperties props;

    private final AtomicLong pending = new AtomicLong(0);
    private final AtomicLong inflight = new AtomicLong(0);
    private final AtomicLong bytes = new AtomicLong(0);
    private final AtomicLong nackTotal = new AtomicLong(0);

    public OutboxMicrometerMetrics(
            ObjectProvider<DegradedStorage> storageProvider,
            NovaOrchestrationProperties props) {
        this.storageProvider = storageProvider;
        this.props = props;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("component", "degraded_outbox");

        registry.gauge("outbox_pending", tags, pending, AtomicLong::get);
        registry.gauge("outbox_inflight", tags, inflight, AtomicLong::get);
        registry.gauge("outbox_bytes", tags, bytes, AtomicLong::get);

        // Prometheus-style *_total monotonic counter via function counter.
        FunctionCounter.builder("outbox_nack_total", nackTotal, AtomicLong::get)
                .tags(tags)
                .register(registry);
    }

    /**
     * Refresh metrics cache.
     *
     * <p>Config: nova.orch.degraded-storage.metrics.refresh-ms (default: 5000)
     */
    @Scheduled(fixedDelayString = "${nova.orch.degraded-storage.metrics.refresh-ms:5000}")
    public void refresh() {
        // Respect config toggles.
        if (!props.getDegradedStorage().isEnabled()
                || !props.getDegradedStorage().getMetrics().isEnabled()) {
            pending.set(0);
            inflight.set(0);
            bytes.set(0);
            // keep nackTotal as-is; it is cumulative and should not be reset when disabled.
            return;
        }

        DegradedStorage storage = storageProvider.getIfAvailable();
        if (!(storage instanceof DegradedStorageWithAck ack)) {
            pending.set(0);
            inflight.set(0);
            bytes.set(0);
            return;
        }

        try {
            DegradedStorageWithAck.OutboxStats stats = ack.stats();
            pending.set(stats.pendingCount());
            inflight.set(stats.inflightCount());
            bytes.set(stats.totalBytes());
            nackTotal.set(stats.nackTotal());
        } catch (Exception e) {
            // Best-effort: do not throw from scheduled metrics refresh.
        }
    }
}
