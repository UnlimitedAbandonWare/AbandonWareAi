package com.example.lms.location.domain;

import jakarta.persistence.*;
import java.time.Instant;



/**
 * Entity storing the most recent known location for a user.  Only the most
 * recent coordinate is persisted to minimise the amount of personally
 * identifiable information stored.  Accuracy and capture time are also
 * recorded to allow consumers to reason about the quality and recency of
 * the coordinate.
 */
@Entity
public class LastLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user identifier associated with this location.  Must match the
     * identifier used when persisting consent.  Uniqueness ensures only
     * one coordinate is stored per user.
     */
    @Column(nullable = false, unique = true)
    private String userId;

    /**
     * Latitude in decimal degrees (WGS84).
     */
    @Column(nullable = false)
    private double latitude;

    /**
     * Longitude in decimal degrees (WGS84).
     */
    @Column(nullable = false)
    private double longitude;

    /**
     * Estimated horizontal accuracy in metres.  Larger values indicate
     * greater uncertainty in the reported coordinate.
     */
    @Column(nullable = false)
    private float accuracy;

    /**
     * The timestamp, in UTC, when this coordinate was captured on the
     * client device.  Provided by the client at ingestion time.
     */
    @Column(nullable = false)
    private Instant capturedAt;

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}