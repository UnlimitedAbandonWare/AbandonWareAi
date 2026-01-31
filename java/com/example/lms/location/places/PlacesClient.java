package com.example.lms.location.places;

import java.util.List;



/**
 * Abstraction for searching nearby points of interest around a given
 * coordinate.  Concrete implementations may wrap external services
 * such as Kakao Local Search, Google Places or other map providers.
 */
public interface PlacesClient {
    /**
     * Query for places matching a particular category or free form text near
     * the given coordinate.  The distance is typically measured in metres.
     *
     * @param lat the latitude of the search centre
     * @param lng the longitude of the search centre
     * @param categoryOrQuery the category (e.g. "pharmacy") or search term
     * @param limit maximum number of results to return
     * @return a list of places ordered by proximity
     */
    List<Place> search(double lat, double lng, String categoryOrQuery, int limit);

    /**
     * Simple value object representing a returned place.  Only the fields
     * relevant for displaying to the user are included.
     *
     * @param name the display name of the place
     * @param address a human readable address
     * @param lat the latitude of the place
     * @param lng the longitude of the place
     * @param distanceM distance in metres from the search centre
     * @param url optional URL pointing to more details about the place
     */
    record Place(String name, String address, double lat, double lng, double distanceM, String url) {}
}