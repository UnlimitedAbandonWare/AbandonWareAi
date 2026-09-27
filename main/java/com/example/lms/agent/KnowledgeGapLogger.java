package com.example.lms.agent;

import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

@Service
public class KnowledgeGapLogger {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGapLogger.class);
    private static final int MAX_EVENTS = 1_024;
    private static final int MAX_QUERY_CHARS = 4_096;
    private static final int MAX_DOMAIN_CHARS = 256;
    private static final int MAX_SUBJECT_CHARS = 512;
    private static final int MAX_INTENT_CHARS = 512;

    public static class GapEvent {
        private final String query;
        private final String domain;
        private final String subject;
        private final String intent;
        private final Instant timestamp;

        public GapEvent(String query, String domain, String subject, String intent) {
            this.query = bounded(query, MAX_QUERY_CHARS);
            this.domain = bounded(domain, MAX_DOMAIN_CHARS);
            this.subject = bounded(subject, MAX_SUBJECT_CHARS);
            this.intent = bounded(intent, MAX_INTENT_CHARS);
            this.timestamp = Instant.now();
        }

        // 👉 Lombok이 안 먹어도 되도록 “수동 게터” 추가
        @JsonIgnore
        public String getQuery()     { return query; }
        @JsonIgnore
        public String getDomain()    { return domain; }
        @JsonIgnore
        public String getSubject()   { return subject; }
        @JsonIgnore
        public String getIntent()    { return intent; }
        @JsonIgnore
        public Instant getTimestamp(){ return timestamp; }

        public String getQueryHash() { return SafeRedactor.hashValue(query); }
        public int getQueryLength()  { return query.length(); }
        public String getDomainHash(){ return SafeRedactor.hashValue(domain); }
        public int getDomainLength() { return domain.length(); }
        public String getSubjectHash(){ return SafeRedactor.hashValue(subject); }
        public int getSubjectLength(){ return subject.length(); }
        public String getIntentHash(){ return SafeRedactor.hashValue(intent); }
        public int getIntentLength() { return intent.length(); }
        public long getTimestampEpochMs(){ return timestamp.toEpochMilli(); }
    }

    private final Object eventsLock = new Object();
    private final ArrayDeque<GapEvent> events = new ArrayDeque<>();

    public void logEvent(String query, String domain, String subject, String intent) {
        GapEvent evt = new GapEvent(query, domain, subject, intent);
        synchronized (eventsLock) {
            events.addLast(evt);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
        log.debug("[KnowledgeGapLogger] Recorded gap: queryHash={}, queryLength={}, domainHash={} domainLength={} subjectHash={} subjectLength={} intentHash={} intentLength={}",
                evt.getQueryHash(), evt.getQueryLength(),
                evt.getDomainHash(), evt.getDomainLength(),
                evt.getSubjectHash(), evt.getSubjectLength(),
                evt.getIntentHash(), evt.getIntentLength());
    }

    public Optional<GapEvent> poll() {
        synchronized (eventsLock) {
            return Optional.ofNullable(events.pollFirst());
        }
    }

    public List<GapEvent> snapshot() {
        synchronized (eventsLock) {
            return new ArrayList<>(events);
        }
    }

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
        synchronized (eventsLock) {
            int count = Math.min(limit, events.size());
            List<GapEvent> recent = new ArrayList<>(count);
            Iterator<GapEvent> iterator = events.descendingIterator();
            while (iterator.hasNext() && recent.size() < count) {
                recent.add(iterator.next());
            }
            Collections.reverse(recent);
            return recent;
        }
    }

    public void clear() {
        synchronized (eventsLock) {
            events.clear();
        }
    }

    private static String bounded(String raw, int maxChars) {
        if (raw == null || maxChars <= 0) {
            return "";
        }
        String value = raw.trim();
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && end < value.length()
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }
}
