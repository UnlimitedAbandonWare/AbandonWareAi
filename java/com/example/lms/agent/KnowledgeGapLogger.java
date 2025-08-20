package com.example.lms.agent;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Simple in-memory logger that records knowledge gap events for later processing.  A knowledge gap
 * occurs when the system fails to answer a user query due to insufficient context or evidence.  The
 * autonomous exploration service can later analyse these events to proactively acquire missing
 * information.
 */
@Slf4j
@Service
public class KnowledgeGapLogger {

    /**
     * Structure capturing the salient details of a knowledge gap event.
     * It includes the original query, inferred domain and subject, optional intent and a timestamp.
     */
    @Getter
    public static class GapEvent {
        private final String query;
        private final String domain;
        private final String subject;
        private final String intent;
        private final Instant timestamp;

        public GapEvent(String query, String domain, String subject, String intent) {
            this.query = query;
            this.domain = domain;
            this.subject = subject;
            this.intent = intent;
            this.timestamp = Instant.now();
        }
    }

    private final ConcurrentLinkedQueue<GapEvent> events = new ConcurrentLinkedQueue<>();

    /**
     * Record a new knowledge gap event.  The caller should supply whatever contextual
     * information is available.  Null values are permitted and will be normalised to empty strings.
     *
     * @param query  the user query that triggered the gap
     * @param domain the inferred domain of the query, may be null
     * @param subject the inferred subject of the query, may be null
     * @param intent an optional intent classification, may be null
     */
    public void logEvent(String query, String domain, String subject, String intent) {
        GapEvent evt = new GapEvent(
                query == null ? "" : query.trim(),
                domain == null ? "" : domain.trim(),
                subject == null ? "" : subject.trim(),
                intent == null ? "" : intent.trim()
        );
        events.add(evt);
        log.debug("[KnowledgeGapLogger] Recorded gap: query='{}', domain='{}', subject='{}', intent='{}'", evt.getQuery(), evt.getDomain(), evt.getSubject(), evt.getIntent());
    }

    /**
     * Poll the oldest recorded knowledge gap event.  Returns an empty optional if none exist.
     *
     * @return an optional containing the next GapEvent, or empty if there are none
     */
    public Optional<GapEvent> poll() {
        return Optional.ofNullable(events.poll());
    }

    /**
     * Return a snapshot of all currently recorded gap events without removing them.  The returned list is
     * a copy and can be freely modified by the caller.
     *
     * @return list of current GapEvents
     */
    public List<GapEvent> snapshot() {
        return events.stream().collect(Collectors.toList());
    }

    /**
     * Clear all recorded events.  Useful after processing or testing.
     */
    public void clear() {
        events.clear();
    }
}