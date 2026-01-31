package scheduler;

import java.util.function.Supplier;

/**
 * Lightweight local scheduler wrapper with back-off that delegates a unit of work.
 * Exists in a distinct package to avoid collision with the Spring component:
 * com.example.lms.scheduler.IndexingScheduler.
 */
public class IndexingScheduler {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Object ocr;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Object embeddingStoreManager;


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
        try {
            if (ocr == null || embeddingStoreManager == null) return;
            java.lang.reflect.Method mScan = ocr.getClass().getMethod("scanNewImages");
            java.util.List spans = (java.util.List) mScan.invoke(ocr);
            java.lang.reflect.Method mChunk = ocr.getClass().getMethod("chunk", java.util.List.class);
            java.util.List chunks = (java.util.List) mChunk.invoke(ocr, spans);
            java.lang.reflect.Method mUpsert = embeddingStoreManager.getClass().getMethod("embedAndUpsert", java.util.List.class);
            mUpsert.invoke(embeddingStoreManager, chunks);
        } catch (Throwable t) { }
    }

}