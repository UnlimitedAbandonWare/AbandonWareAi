// src/main/java/service/tools/outbox/OutboxMessage.java
package service.tools.outbox;

import java.time.Instant;
import java.util.UUID;

public class OutboxMessage {
    public final String id = UUID.randomUUID().toString();
    public final String channel;      // e.g., "kakao","email","webhook"
    public final String payloadJson;  // 전송 바디(JSON 직렬화)
    public final Instant createdAt = Instant.now();
    public OutboxMessage(String channel, String payloadJson) {
        this.channel = channel; this.payloadJson = payloadJson;
    }
}