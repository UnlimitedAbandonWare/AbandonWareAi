package com.example.lms.agent;

import lombok.Getter;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

@Service
public class KnowledgeGapLogger {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGapLogger.class);

    public static class GapEvent {
        private final String query;
        private final String domain;
        private final String subject;
        private final String intent;
        private final Instant timestamp;

        public GapEvent(String query, String domain, String subject, String intent) {
            this.query = query == null ? "" : query.trim();
            this.domain = domain == null ? "" : domain.trim();
            this.subject = subject == null ? "" : subject.trim();
            this.intent = intent == null ? "" : intent.trim();
            this.timestamp = Instant.now();
        }

        // 👉 Lombok이 안 먹어도 되도록 “수동 게터” 추가
        public String getQuery()     { return query; }
        public String getDomain()    { return domain; }
        public String getSubject()   { return subject; }
        public String getIntent()    { return intent; }
        public Instant getTimestamp(){ return timestamp; }
    }

    private final ConcurrentLinkedQueue<GapEvent> events = new ConcurrentLinkedQueue<>();

    public void logEvent(String query, String domain, String subject, String intent) {
        GapEvent evt = new GapEvent(query, domain, subject, intent);
        events.add(evt);
        log.debug("[KnowledgeGapLogger] Recorded gap: query='{}', domain='{}', subject='{}', intent='{}'",
                evt.getQuery(), evt.getDomain(), evt.getSubject(), evt.getIntent());
    }

    public Optional<GapEvent> poll() { return Optional.ofNullable(events.poll()); }

    public List<GapEvent> snapshot() { return events.stream().collect(Collectors.toList()); }

    /**
     * Returns at most the most recent {@code limit} gap events.
     *
     * <p>Insertion order is preserved (oldest→newest within the returned list).
     * This is intentionally fail-soft and avoids leaking raw chat context.
     * </p>
     */
    public List<GapEvent> snapshotRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<GapEvent> all = snapshot();
        int size = all.size();
        if (size <= limit) {
            return all;
        }
        int from = Math.max(0, size - limit);
        return new java.util.ArrayList<>(all.subList(from, size));
    }

    public void clear() { events.clear(); }
}