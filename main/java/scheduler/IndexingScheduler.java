package scheduler;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;

/**
 * Lightweight local scheduler wrapper with back-off that delegates a unit of work.
 * Exists in a distinct package to avoid collision with the Spring component:
 * com.example.lms.scheduler.IndexingScheduler.
 */
public class IndexingScheduler {
    private static final Logger log = Logger.getLogger(IndexingScheduler.class.getName());

    private final IndexJobLock lock = new IndexJobLock();

    /**
     * Run the given job up to 3 times with back-off (250/500/1000ms) if it returns false.
     * Returns immediately if lock is already held.
     */
    public void runWithRetry(Supplier<Boolean> job) throws InterruptedException {
        if (job == null) return;
        if (!lock.tryLock()) return;
        try {
            long[] backoff = new long[]{250, 500, 1000};
            for (int i = 0; i < backoff.length; i++) {
                if (Boolean.TRUE.equals(job.get())) {
                    return;
                }
                Thread.sleep(backoff[i]);
            }
        } finally {
            lock.unlock();
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(cron="${indexing.ocr.cron:0 */10 * * * *}")
    public void runOcrIndexing() {
        logFailSoft("ocrIndexing.disabled", null);
    }

    private static void logFailSoft(String stage, Throwable t) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = t == null ? "unknown" : t.getClass().getSimpleName();
            log.fine("[AWX][scheduler][indexing] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
