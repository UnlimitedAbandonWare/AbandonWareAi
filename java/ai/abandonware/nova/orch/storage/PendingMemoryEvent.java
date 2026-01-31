package ai.abandonware.nova.orch.storage;

import java.time.Instant;

/**
 * Minimal pending memory record (privacy-friendly).
 */
public record PendingMemoryEvent(
                String sessionKey,
                String contextKey,
                String userQueryHash,
                String answerSnippet,
                Instant occurredAt,
                long sizeBytes,
                String reason) {
        // 기존 코드와의 하위 호환성을 위해 timestamp() 메서드 추가
        public Instant timestamp() {
                return occurredAt;
        }

        // 단순화된 생성자 (하위 호환성)
        public PendingMemoryEvent(
                        String sessionKey,
                        String contextKey,
                        String userQueryHash,
                        String answerSnippet,
                        Instant occurredAt,
                        long sizeBytes) {
                this(sessionKey, contextKey, userQueryHash, answerSnippet, occurredAt, sizeBytes, null);
        }

        // MemoryDegradedAspect에서 사용하는 생성자 (Instant, sessionKey, userQueryHash,
        // answerSnippet, reason)
        public PendingMemoryEvent(Instant occurredAt, String sessionKey, String userQueryHash, String answerSnippet,
                        String reason) {
                this(sessionKey, null, userQueryHash, answerSnippet, occurredAt, 0L, reason);
        }
}
