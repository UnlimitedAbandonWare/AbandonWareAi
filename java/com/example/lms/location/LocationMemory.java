package com.example.lms.location;

import com.example.lms.common.ChatSessionScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Session‑scoped storage for the most recent geolocation supplied by a client.
 *
 * <p>This component maintains an in‑memory cache keyed by the chat session
 * identifier.  Locations expire after a fixed period of inactivity (2 hours).
 * The cache is capped at 10 000 entries to prevent unbounded memory usage.
 *
 * <p>Coordinates are represented by the {@link GeoPoint} record which
 * encapsulates latitude, longitude and an optional accuracy value.</p>
 */
@Component
public class LocationMemory {

    /**
     * Underlying Caffeine cache keyed by session ID.
     */
    private final Cache<Long, GeoPoint> store = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(2))
            .maximumSize(10_000)
            .build();

    /**
     * Persist a coordinate for the given session.  Null values are ignored.
     *
     * @param sessionId the chat session identifier
     * @param p         the coordinate to store
     */
    public void put(Long sessionId, GeoPoint p) {
        if (sessionId != null && p != null) {
            store.put(sessionId, p);
        }
    }

    /**
     * Retrieve the stored coordinate for the specified session.
     *
     * @param sessionId the chat session identifier
     * @return an {@code Optional} describing the coordinate, or empty if no
     * coordinate is present or the entry has expired
     */
    public Optional<GeoPoint> get(Long sessionId) {
        return Optional.ofNullable(store.getIfPresent(sessionId));
    }

    /**
     * Retrieve the coordinate associated with the current thread’s session,
     * determined via {@link ChatSessionScope#current()}.  If no session ID
     * has been bound, an empty {@code Optional} is returned.
     *
     * @return an optional coordinate for the current session
     */
    public Optional<GeoPoint> current() {
        return Optional.ofNullable(ChatSessionScope.current()).flatMap(this::get);
    }

    /**
     * Simple immutable record capturing a point on the Earth and an optional
     * accuracy radius in metres.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @param acc optional horizontal accuracy in metres
     */
    public record GeoPoint(double lat, double lon, Double acc) { }
}