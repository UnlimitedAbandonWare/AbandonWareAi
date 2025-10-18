package com.example.lms.location.api;

import com.example.lms.location.dto.LocationEventDto;
import com.example.lms.location.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;



/**
 * REST controller responsible for managing the user's consent and location
 * events.  Endpoints under {@code /api/location} are secured with
 * Spring Security; the authenticated principal's username is used as
 * the user identifier.  Clients not performing authentication may
 * choose to supply the X-User-Id header in place of a principal.
 */
@RestController
@RequestMapping("/api/location")
@RequiredArgsConstructor
public class LocationController {
    private final LocationService svc;

    /**
     * Toggle location consent for the current user.  When the authenticated
     * principal is present its username is used; otherwise the optional
     * X-User-Id header is used.  If both are missing the request will
     * respond with HTTP 400.
     *
     * @param onOff either "on" or "off" (case insensitive)
     * @param principal the authenticated user (may be null)
     * @param userId optional user identifier supplied by the client
     * @return HTTP 200 on success or HTTP 400 for invalid input
     */
    @PostMapping("/consent/{onOff}")
    public ResponseEntity<?> setConsent(@PathVariable String onOff,
                                        @AuthenticationPrincipal UserDetails principal,
                                        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String uid = extractUserId(principal, userId);
        if (uid == null || uid.isBlank()) {
            return ResponseEntity.badRequest().body("Missing user identifier");
        }
        boolean enabled = "on".equalsIgnoreCase(onOff);
        svc.setConsent(uid, enabled);
        return ResponseEntity.ok().build();
    }

    /**
     * Ingest a location event for the current user.  Requires that consent
     * has been granted previously.  When consent is disabled this endpoint
     * responds with HTTP 412 (Precondition Failed).
     *
     * @param dto the location event body
     * @param principal the authenticated user (may be null)
     * @param userId optional user identifier supplied by the client
     * @return HTTP 202 Accepted when the event is stored, HTTP 412 if
     *         consent is disabled or HTTP 400 when no user id is provided
     */
    @PostMapping("/events")
    public ResponseEntity<?> ingest(@RequestBody LocationEventDto dto,
                                    @AuthenticationPrincipal UserDetails principal,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String uid = extractUserId(principal, userId);
        if (uid == null || uid.isBlank()) {
            return ResponseEntity.badRequest().body("Missing user identifier");
        }
        if (!svc.isEnabled(uid)) {
            return ResponseEntity.status(412).body("Location consent is OFF");
        }
        svc.saveEvent(uid, dto);
        return ResponseEntity.accepted().build();
    }

    private String extractUserId(UserDetails principal, String headerId) {
        if (principal != null) {
            return principal.getUsername();
        }
        return headerId;
    }
}