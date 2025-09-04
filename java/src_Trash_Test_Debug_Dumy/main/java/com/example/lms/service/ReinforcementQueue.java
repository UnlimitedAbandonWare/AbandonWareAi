package com.example.lms.service.reinforcement;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.ArrayList;

/**
 * A thread‑safe buffer for queuing {@link ReinforcementTask} instances to be processed in mini‑batches.
 */
public class ReinforcementQueue {
    private final Queue<ReinforcementTask> buffer = new ConcurrentLinkedQueue<>();

    /** Enqueue a task for later processing. */
    public void push(ReinforcementTask task) {
        buffer.add(task);
    }

    /** Drain all currently queued tasks into a list and clear the queue. */
    public List<ReinforcementTask> drain() {
        List<ReinforcementTask> list = new ArrayList<>();
        ReinforcementTask t;
        while ((t = buffer.poll()) != null) {
            list.add(t);
        }
        return list;
    }
}
