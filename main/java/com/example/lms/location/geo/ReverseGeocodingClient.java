package com.example.lms.location.geo;

import java.util.Optional;



/**
 * Port interface for performing reverse geocoding.  Implementations
 * convert latitude/longitude coordinates into a human readable address
 * structure.  Clients should treat this API as fail-soft-an empty
 * {@link Optional} indicates that no address information could be
 * resolved for the provided coordinate.  Implementations must not
 * throw and should internally handle connectivity or parsing errors.
 */
public interface ReverseGeocodingClient {

    /**
     * Simple record representing a resolved address.  The city and
     * district fields correspond to the first and second administrative
     * levels respectively (시/도 and 시/군/구 in Korea).  The road
     * field may contain a road name or full road address when
     * available; it is optional and may be {@code null} when no road
     * information is present.  Callers should favour road when
     * non-blank and otherwise fall back to the city/district pair.
     *
     * @param city    administrative region (e.g. 서울특별시)
     * @param district second level region (e.g. 강남구)
     * @param road    road name or address (may be null)
     */
    record Address(String city, String district, String road) {}

    /**
     * Perform a reverse geocoding lookup for the supplied latitude and
     * longitude.  Coordinates should be WGS84 decimal degrees.  The
     * returned {@link Address} will contain the best available
     * administrative and road level details.  When no match is found
     * or an error occurs an empty {@link Optional} will be returned.
     *
     * @param lat latitude in decimal degrees
     * @param lng longitude in decimal degrees
     * @return an optional address
     */
    Optional<Address> reverse(double lat, double lng);
}