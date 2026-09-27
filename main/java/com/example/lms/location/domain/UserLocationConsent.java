package com.example.lms.location.domain;

import jakarta.persistence.*;
import java.time.Instant;



/**
 * Entity capturing whether a given user has granted consent for the application
 * to access and utilise their current location.  Consent is stored per user
 * identifier and updated each time the user toggles the location feature on
 * or off.  See {@link com.example.lms.location.api.LocationController} for
 * the REST endpoints that manipulate this record.
 */
@Entity
public class UserLocationConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The logical identifier for the user.  When authentication is enabled
     * this corresponds to the username of the current principal.  If the
     * application is running in anonymous mode this field may capture a
     * session identifier or other opaque token.
     */
    @Column(nullable = false, unique = true)
    private String userId;

    /**
     * Whether the user has enabled location based features.  When false
     * no location data will be persisted and calls to location specific
     * operations will be short circuited.
     */
    @Column(nullable = false)
    private boolean enabled;

    /**
     * Timestamp tracking when this record was last modified.  Useful for
     * auditing and for potential expiry of stale consents.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    public static UserLocationConsent of(String userId, boolean enabled) {
        UserLocationConsent e = new UserLocationConsent();
        e.userId = userId;
        e.enabled = enabled;
        e.updatedAt = Instant.now();
        return e;
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}