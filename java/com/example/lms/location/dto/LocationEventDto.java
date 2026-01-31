package com.example.lms.location.dto;


/**
 * Data Transfer Object representing a location event as reported by the
 * client application.  Latitude and longitude are required while
 * accuracy and timestamp are optional.  The {@code source} field
 * identifies whether the coordinate was captured automatically or via
 * an explicit user action.
 *
 * @param latitude the latitude in decimal degrees
 * @param longitude the longitude in decimal degrees
 * @param accuracy optional horizontal accuracy in metres (may be null)
 * @param timestampMs optional UNIX epoch timestamp in milliseconds (may be null)
 * @param source optional descriptive tag for the event origin
 */
public record LocationEventDto(
        double latitude,
        double longitude,
        Float accuracy,
        Long timestampMs,
        String source
) {}