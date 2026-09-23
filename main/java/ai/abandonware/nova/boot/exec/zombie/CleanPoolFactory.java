package ai.abandonware.nova.boot.exec.zombie;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

public class CleanPoolFactory {
    private final ZombieBreederProperties props;
    private final IntSupplier availableProcessors;

    public CleanPoolFactory(ZombieBreederProperties props) {
        this(props, () -> Runtime.getRuntime().availableProcessors());
    }

    CleanPoolFactory(ZombieBreederProperties props, IntSupplier availableProcessors) {
        this.props = Objects.requireNonNullElseGet(props, ZombieBreederProperties::new);
        this.availableProcessors = Objects.requireNonNull(availableProcessors, "availableProcessors");
    }

    public CancelShieldExecutorService createCleanPool(String originalOwnerHint) {
        return createCleanPool(originalOwnerHint, props.getMaxContainmentPoolSize());
    }

    public CancelShieldExecutorService createCleanPool(String originalOwnerHint, int currentInflight) {
        int poolSize = choosePoolSize(currentInflight);
        String ownerHash = ownerHash(originalOwnerHint);
        String poolTag = "cleanPool:" + ownerHash;
        ExecutorService delegate = Executors.newFixedThreadPool(poolSize, threadFactory(ownerHash));
        TraceStore.put("zombie.cleanPool.created", true);
        TraceStore.put("zombie.cleanPool.poolTag", poolTag);
        TraceStore.put("zombie.cleanPool.maxSize", poolSize);
        TraceStore.put("zombie.cleanPool.configuredMaxSize", normalizedConfiguredMax());
        return new CancelShieldExecutorService(delegate, poolTag, poolSize);
    }

    int choosePoolSize(int currentInflight) {
        int configuredMax = normalizedConfiguredMax();
        int cpuShare = Math.max(1, safeProcessors() / 2);
        int workload = Math.max(1, currentInflight);
        return Math.max(1, Math.min(configuredMax, Math.min(cpuShare, workload)));
    }

    public void retire(CancelShieldExecutorService cleanPool, String poolTag) {
        if (cleanPool == null) {
            TraceStore.put("zombie.cleanPool.retired", false);
            TraceStore.put("zombie.cleanPool.drainedOk", false);
            return;
        }
        boolean drained = false;
        try {
            cleanPool.shutdown();
            drained = cleanPool.awaitTermination(Math.max(1L, props.getDrainTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            TraceStore.put("zombie.cleanPool.retireError", "interrupted");
        } catch (Throwable error) {
            TraceStore.put("zombie.cleanPool.retireError",
                    SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown"));
            TraceStore.put("zombie.cleanPool.retireErrorHash", SafeRedactor.hashValue(messageOf(error)));
            TraceStore.put("zombie.cleanPool.retireErrorLength", messageLength(error));
        }
        TraceStore.put("zombie.cleanPool.retired", true);
        TraceStore.put("zombie.cleanPool.drainedOk", drained);
        TraceStore.put("zombie.cleanPool.retiredPoolTag", SafeRedactor.traceLabelOrFallback(poolTag, "unknown"));
    }

    private int normalizedConfiguredMax() {
        return Math.max(1, Math.min(128, props.getMaxContainmentPoolSize()));
    }

    private int safeProcessors() {
        try {
            return Math.max(1, availableProcessors.getAsInt());
        } catch (Throwable error) {
            TraceStore.put("zombie.cleanPool.cpuProbeError",
                    SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown"));
            return 1;
        }
    }

    private static ThreadFactory threadFactory(String ownerHash) {
        AtomicInteger sequence = new AtomicInteger();
        String safeHash = ownerHash == null ? "hash-unknown" : ownerHash.replace(':', '-');
        return runnable -> {
            Thread thread = new Thread(runnable, "zombie-clean-" + safeHash + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String ownerHash(String ownerHint) {
        String hash = SafeRedactor.hashValue(ownerHint);
        return hash == null || hash.isBlank() ? "hash:unknown" : hash;
    }

    private static String messageOf(Throwable error) {
        return error == null ? null : error.getMessage();
    }

    private static int messageLength(Throwable error) {
        String message = messageOf(error);
        return message == null ? 0 : message.length();
    }
}
