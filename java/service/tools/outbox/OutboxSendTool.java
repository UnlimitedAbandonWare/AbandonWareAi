// src/main/java/service/tools/outbox/OutboxSendTool.java
package service.tools.outbox;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class OutboxSendTool {
    private final OutboxRepository repo;
    public OutboxSendTool(OutboxRepository repo) { this.repo = repo; }

    /**
     * 기본 전송(primary)이 실패하면 아웃박스 큐에 적재하고 true 반환(파이프라인 계속).
     * 성공 시 true, 실패+적재 시 true, 실패+미적재 시 false
     */
    public boolean sendOrEnqueue(String channel, String payloadJson, Supplier<Boolean> primary) {
        try {
            Boolean ok = primary.get();
            if (Boolean.TRUE.equals(ok)) return true;
        } catch (Exception ignore) {
            // fall through
        }
        repo.save(new OutboxMessage(channel, payloadJson));
        return true;
    }
}