package com.example.lms.location.route;

/**
 * Minimal Directions client placeholder used to represent travel time
 * estimates.  This implementation provides only the data holder used by
 * {@code Formatters#renderEta} and does not perform any external API
 * calls.  A full implementation can replace this class and supply
 * additional behaviour as needed.
 */
public final class DirectionsClient {

    private DirectionsClient() {
        // Prevent instantiation
    }

    /**
     * Data class representing an estimated time of arrival.  Instances
     * encapsulate a duration in seconds and an optional human readable
     * description that can override the default formatting.
     */
    public static final class EtaResult {
        private final long seconds;
        private final String description;

        /**
         * Construct an ETA result.
         *
         * @param seconds     the estimated travel time in seconds
         * @param description a human friendly description (may be {@code null})
         */
        public EtaResult(long seconds, String description) {
            this.seconds = seconds;
            this.description = description;
        }

        /**
         * Return the travel time in seconds.
         *
         * @return the number of seconds
         */
        public long seconds() {
            return seconds;
        }

        /**
         * Return an optional human readable description of the ETA.  When
         * {@code null} or blank the default message is used.
         *
         * @return a descriptive string or {@code null}
         */
        public String description() {
            return description;
        }
    }
}