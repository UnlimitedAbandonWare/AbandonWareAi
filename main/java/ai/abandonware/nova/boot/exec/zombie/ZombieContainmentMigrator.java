package ai.abandonware.nova.boot.exec.zombie;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.List;
import java.util.concurrent.Callable;

public class ZombieContainmentMigrator {
    private final ZombieBreederProperties props;

    public ZombieContainmentMigrator(ZombieBreederProperties props) {
        this.props = props == null ? new ZombieBreederProperties() : props;
    }

    public MigrationResult migrate(
            CancelShieldExecutorService contaminatedPool,
            List<NamedCallable<?>> pendingTasks,
            CancelShieldExecutorService cleanPool) {
        int migrated = 0;
        int skipped = 0;
        boolean safe = true;
        if (cleanPool == null || pendingTasks == null || pendingTasks.isEmpty()) {
            MigrationResult result = new MigrationResult(0, 0, pendingTasks == null ? 0 : pendingTasks.size(), cleanPool != null);
            record(result);
            return result;
        }
        for (NamedCallable<?> task : pendingTasks) {
            if (task == null || task.callable() == null) {
                skipped++;
                continue;
            }
            try {
                cleanPool.submit(task.callable());
                migrated++;
            } catch (Throwable error) {
                safe = false;
                TraceStore.put("zombie.migrator.error",
                        SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown"));
                TraceStore.put("zombie.migrator.errorHash", SafeRedactor.hashValue(messageOf(error)));
                TraceStore.put("zombie.migrator.errorLength", messageLength(error));
            }
        }
        MigrationResult result = new MigrationResult(migrated, 0, skipped, safe);
        record(result);
        return result;
    }

    void record(MigrationResult result) {
        MigrationResult safe = result == null ? new MigrationResult(0, 0, 0, false) : result;
        TraceStore.put("zombie.migrator.migratedCount", safe.migratedCount());
        TraceStore.put("zombie.migrator.zombieKilled", safe.zombieKilled());
        TraceStore.put("zombie.migrator.alreadyDone", safe.alreadyDone());
        TraceStore.put("zombie.migrator.pipelineSafe", safe.pipelineSafe());
        TraceStore.put("zombie.migrator.verdict", safe.pipelineSafe()
                ? (safe.migratedCount() > 0 ? "MIGRATION_OK" : "MIGRATION_PARTIAL")
                : "MIGRATION_FAILED");
    }

    @SuppressWarnings("unused")
    public ZombieBreederProperties properties() {
        return props;
    }

    private static String messageOf(Throwable error) {
        return error == null ? null : error.getMessage();
    }

    private static int messageLength(Throwable error) {
        String message = messageOf(error);
        return message == null ? 0 : message.length();
    }

    public record MigrationResult(int migratedCount, int zombieKilled, int alreadyDone, boolean pipelineSafe) {
    }

    public record NamedCallable<T>(String tag, Callable<T> callable) {
    }
}
