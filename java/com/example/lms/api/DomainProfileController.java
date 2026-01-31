package com.example.lms.api;

import com.example.lms.service.rag.auth.DomainProfileLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;




/**
 * REST controller exposing the available domain allowlist profiles and a
 * reload endpoint for administrators.  The GET endpoint is publicly
 * accessible and returns the default profile along with a summary of
 * each available profile.  The POST reload endpoint requires either an
 * authenticated user with the ROLE_ADMIN authority or a valid admin
 * token supplied in the {@code X-Admin-Token} header.  When no admin
 * token is configured the reload endpoint is effectively open (it is
 * assumed that access control will be enforced at a higher level).
 */
@RestController
@RequestMapping("/api/domain/profiles")
public class DomainProfileController {

    private final DomainProfileLoader loader;

    public DomainProfileController(DomainProfileLoader loader) {
        this.loader = loader;
    }

    /**
     * List all configured domain profiles.  The returned object contains
     * the name of the default profile and a list of all profiles with
     * their respective entry counts.  This endpoint is intended for
     * consumption by the frontend to populate a selection dropdown.
     */
    @GetMapping
    public Map<String, Object> listProfiles() {
        Map<String, Object> out = new HashMap<>();
        out.put("default", loader.getDefaultProfile());
        out.put("profiles", loader.listProfiles());
        return out;
    }

    /**
     * Reload the domain profiles.  This operation will re-read any
     * external profile definitions and rebuild the internal caches.  It
     * returns the updated list of profiles upon success.  The caller must
     * either be authenticated with ROLE_ADMIN or provide a matching
     * admin token via the {@code X-Admin-Token} header.  When no admin
     * token is configured in the application properties the reload is
     * permitted without a header.
     *
     * @param token optional admin token from the request header
     * @param auth the current authentication (may be null)
     * @return a ResponseEntity containing the updated profile list or
     *         403 Forbidden if authorisation fails
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                                      Authentication auth) {
        boolean allowed = false;
        // Check if authenticated user has ROLE_ADMIN
        try {
            if (auth != null && auth.isAuthenticated() && auth.getAuthorities() != null) {
                allowed = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            }
        } catch (Exception ignore) {
            // ignore
        }
        // Check admin token if not already allowed
        if (!allowed) {
            String configured = loader.getAdminToken();
            if (configured != null && !configured.isBlank()) {
                if (configured.equals(token)) {
                    allowed = true;
                }
            } else {
                // no configured token → permit reload
                allowed = true;
            }
        }
        if (!allowed) {
            return ResponseEntity.status(403).build();
        }
        loader.reload();
        Map<String, Object> out = new HashMap<>();
        out.put("default", loader.getDefaultProfile());
        out.put("profiles", loader.listProfiles());
        return ResponseEntity.ok(out);
    }
}