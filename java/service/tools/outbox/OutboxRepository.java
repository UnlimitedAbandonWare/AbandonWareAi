// src/main/java/service/tools/outbox/OutboxRepository.java
package service.tools.outbox;

import org.springframework.stereotype.Repository;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Repository
public class OutboxRepository {
    private final Queue<OutboxMessage> q = new ConcurrentLinkedQueue<>();
    public void save(OutboxMessage m) { q.add(m); }
    public OutboxMessage poll() { return q.poll(); }
    public int size() { return q.size(); }
}