package com.example.lms.location.route;

import com.example.lms.location.domain.LastLocation;



/**
 * Abstraction for computing routing/ETA between a user's last known
 * location and a destination. Implementations may integrate with
 * Tmap, Google, or other providers.
 */
public interface DirectionsClient {

    /**
     * Minimal ETA result used by Formatters and services.
     *
     * @param seconds     estimated travel time in seconds
     * @param description optional human readable summary (may be null/blank)
     */
    record EtaResult(double seconds, String description) {
    }

    /**
     * Compute ETA from the user's last location to a free-form destination.
     * Implementations may return {@code null} when routing cannot be computed.
     *
     * @param from        the user's last known location
     * @param destination the free-form text destination
     * @return a minimal ETA result or {@code null} when unavailable
     */
    EtaResult eta(LastLocation from, String destination);
}