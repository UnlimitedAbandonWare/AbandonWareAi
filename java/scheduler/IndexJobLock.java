
    package scheduler;
    import java.util.concurrent.atomic.AtomicBoolean;
    public class IndexJobLock {
        private final AtomicBoolean lock = new AtomicBoolean(false);
        public boolean tryLock() { return lock.compareAndSet(false, true); }
        public void unlock() { lock.set(false); }
    }
    