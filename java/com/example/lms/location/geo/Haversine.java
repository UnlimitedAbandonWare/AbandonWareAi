package com.example.lms.location.geo;


/**
 * Utility class providing a Haversine distance calculation.  The Haversine
 * formula computes the great-circle distance between two points on the
 * Earth's surface specified by their latitude and longitude.  This is
 * useful when estimating distances in the absence of more precise
 * routing information.  Distances are returned in metres.
 */
public final class Haversine {

    // Mean radius of the Earth in metres.  This constant is used by the
    // Haversine formula to convert angular distances into linear metres.
    private static final double EARTH_RADIUS_M = 6371000.0;

    private Haversine() {
        // prevent instantiation
    }

    /**
     * Compute the great-circle distance between two points on the Earth's
     * surface using the Haversine formula.  Coordinates should be
     * expressed in decimal degrees.
     *
     * @param lat1 latitude of the first point in degrees
     * @param lon1 longitude of the first point in degrees
     * @param lat2 latitude of the second point in degrees
     * @param lon2 longitude of the second point in degrees
     * @return the distance between the two points in metres
     */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
                   Math.cos(phi1) * Math.cos(phi2) *
                   Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}