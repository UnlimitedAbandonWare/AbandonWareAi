package com.example.lms.location.intent;


/**
 * Enumeration of high level user intents that trigger location based
 * functionality.  Detection is performed by the {@link LocationIntentDetector}
 * based on simple keyword heuristics.  Each intent corresponds to a
 * different action in the {@link com.example.lms.location.LocationService}.
 */
public enum LocationIntent {
    /**
     * The user is asking "Where am I?".  Typically responded to with
     * coordinates or a short description of the current area.
     */
    WHERE_AM_I,
    /**
     * The user is requesting a general briefing about the area or
     * neighbourhood they are in.  A descriptive summary should be
     * generated using the language model when possible.
     */
    AREA_BRIEF,
    /**
     * The user is searching for a nearby point of interest such as a
     * pharmacy or cafe.  Results are returned from the configured
     * {@link com.example.lms.location.places.PlacesClient}.
     */
    NEARBY_POI,
    /**
     * The user is enquiring about travel time to a specified destination.
     * This intent will call into the configured directions API.
     */
    TRAVEL_TIME,
    /**
     * Fallback intent indicating no location specific processing is
     * required.
     */
    NONE
}