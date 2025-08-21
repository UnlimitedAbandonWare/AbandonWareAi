package com.example.lms.location;

import com.example.lms.location.domain.LastLocation;
import com.example.lms.location.domain.UserLocationConsent;
import com.example.lms.location.dto.LocationEventDto;
import com.example.lms.location.intent.LocationIntent;
import com.example.lms.location.intent.LocationIntentDetector;
import com.example.lms.location.repo.LastLocationRepository;
import com.example.lms.location.repo.UserLocationConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Service encapsulating the core location logic for consent management,
 * persistence of the most recent location and intent detection.  This
 * service should be used by controllers and higher level handlers to
 * determine whether a location query can be answered and to access the
 * most recent coordinates for a user.
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class LocationService {
    private final UserLocationConsentRepository consentRepo;
    private final LastLocationRepository locationRepo;
    private final LocationIntentDetector intentDetector;
    /**
     * Reverse geocoding client used to convert coordinates into
     * human readable addresses.  May be a no‑op implementation when
     * no API key is configured.
     */
    private final com.example.lms.location.geo.ReverseGeocodingClient reverseGeocodingClient;

    /**
     * Cache of the last resolved address per user.  When a location event is
     * reported the reverse geocoding client is invoked and the result is
     * stored here.  Downstream services may retrieve the most recently
     * resolved address via {@link #getResolvedAddress(String)}.  The cache
     * is intentionally simple and does not expire entries automatically as
     * subsequent location events will overwrite the previous value.
     */
    private final java.util.Map<String, com.example.lms.location.geo.ReverseGeocodingClient.Address> lastResolved = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Persist or update the user's consent for location based features.  If
     * a record does not already exist for the user one will be created.
     *
     * @param userId the logical identifier for the user
     * @param enabled whether consent is granted
     */
    public void setConsent(String userId, boolean enabled) {
        if (userId == null || userId.isBlank()) return;
        UserLocationConsent rec = consentRepo.findByUserId(userId)
                .orElseGet(() -> UserLocationConsent.of(userId, enabled));
        rec.setEnabled(enabled);
        rec.setUpdatedAt(Instant.now());
        consentRepo.save(rec);
    }

    /**
     * Determine whether the user has opted into location features.  When no
     * consent record exists this returns {@code false}.
     *
     * @param userId the logical identifier for the user
     * @return true when consent is enabled
     */
    public boolean isEnabled(String userId) {
        if (userId == null || userId.isBlank()) return false;
        return consentRepo.findByUserId(userId).map(UserLocationConsent::isEnabled).orElse(false);
    }

    /**
     * Persist a location event reported by the client.  The latitude and
     * longitude are required while accuracy and timestamp may be null.  If
     * consent is not enabled for the given user the event will not be
     * persisted.
     *
     * @param userId the logical identifier for the user
     * @param dto the location event DTO received from the client
     */
    public void saveEvent(String userId, LocationEventDto dto) {
        if (!isEnabled(userId) || dto == null) {
            return;
        }
        LastLocation loc = locationRepo.findByUserId(userId).orElseGet(LastLocation::new);
        loc.setUserId(userId);
        loc.setLatitude(dto.latitude());
        loc.setLongitude(dto.longitude());
        loc.setAccuracy(dto.accuracy() == null ? 0.0f : dto.accuracy());
        // Use provided timestamp if present; fallback to now
        long ts = (dto.timestampMs() != null) ? dto.timestampMs() : System.currentTimeMillis();
        loc.setCapturedAt(Instant.ofEpochMilli(ts));
        locationRepo.save(loc);

        // Resolve a human readable address on the fly.  Fail softly if the
        // geocoding service is unavailable or returns no result.  The
        // resolved address is cached per user for reuse by query
        // preprocessors and other services.  Missing or blank addresses
        // simply result in the absence of a cache entry.
        try {
            var optAddr = reverseGeocodingClient.reverse(dto.latitude(), dto.longitude());
            optAddr.ifPresent(addr -> lastResolved.put(userId, addr));
        } catch (Exception ignore) {
            // swallow all exceptions; caching is best effort
        }
    }

    /**
     * Retrieve the most recently persisted location for the given user.
     *
     * @param userId the logical identifier for the user
     * @return an Optional containing the location when present
     */
    public Optional<LastLocation> lastLocation(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        return locationRepo.findByUserId(userId);
    }

    /**
     * Retrieve the most recently resolved address for the supplied user.  The
     * returned {@link java.util.Optional} will be empty when no address has
     * been resolved yet or when the last geocoding attempt failed.  This
     * method does not invoke the geocoding client; it simply returns the
     * cached value from the last location update.
     *
     * @param userId the logical user identifier
     * @return the cached resolved address if present
     */
    public java.util.Optional<com.example.lms.location.geo.ReverseGeocodingClient.Address> getResolvedAddress(String userId) {
        if (userId == null || userId.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(lastResolved.get(userId));
    }

    /**
     * Alias for {@link #lastLocation(String)} exposing a more expressive
     * name.  Consumers should use this when they intend to retrieve
     * the latest known coordinate for a user prior to composing a
     * personalised response.
     *
     * @param userId the logical identifier for the user
     * @return an optional containing the most recent location
     */
    public Optional<LastLocation> getLast(String userId) {
        return lastLocation(userId);
    }

    /**
     * Produce a deterministic answer to the "where am I" intent for the
     * supplied user.  When location consent is enabled and a recent
     * coordinate exists this method will attempt to resolve a human
     * readable address using the configured reverse geocoding client.
     * The response prioritises road level detail when available and
     * falls back to city/district or raw coordinates in decreasing
     * order of specificity.  All failures result in an empty optional
     * allowing callers to gracefully fall back to LLM/RAG flows.
     *
     * @param userId the logical identifier for the user
     * @return a personalised location message or empty when no
     *         information is available
     */
    public Optional<String> answerWhereAmI(String userId) {
        if (userId == null || userId.isBlank()) {
            log.info("answerWhereAmI: userId is blank");
            return Optional.empty();
        }
        // Ensure consent is enabled
        if (!isEnabled(userId)) {
            log.info("answerWhereAmI: user {} has not enabled location consent", userId);
            return Optional.empty();
        }
        var lastOpt = locationRepo.findByUserId(userId);
        if (lastOpt.isEmpty()) {
            log.info("answerWhereAmI: no last location recorded for user {}", userId);
            return Optional.empty();
        }
        LastLocation loc = lastOpt.get();
        // Accuracy check: if accuracy is too large skip geocoding
        float acc = loc.getAccuracy();
        // If accuracy is unreasonably high (> 2km) fall back to coordinate message
        boolean skipGeocode = acc > 2000.0f;
        if (!skipGeocode) {
            try {
                var addressOpt = reverseGeocodingClient.reverse(loc.getLatitude(), loc.getLongitude());
                if (addressOpt.isPresent()) {
                    var a = addressOpt.get();
                    if (a.road() != null && !a.road().isBlank()) {
                        String msg = String.format("현재 %s 근처에 계신 것으로 보여요.", a.road());
                        log.info("answerWhereAmI: resolved road level address for user {}: {}", userId, msg);
                        return Optional.of(msg);
                    } else if (a.city() != null && a.district() != null
                            && !a.city().isBlank() && !a.district().isBlank()) {
                        String msg = String.format("현재 %s %s 인근에 계신 것으로 보여요.", a.city(), a.district());
                        log.info("answerWhereAmI: resolved city/district for user {}: {}", userId, msg);
                        return Optional.of(msg);
                    }
                }
            } catch (Exception e) {
                log.debug("answerWhereAmI: reverse geocoding threw", e);
            }
        } else {
            log.info("answerWhereAmI: skipping reverse geocoding due to high accuracy {}m", acc);
        }
        // Fallback to raw coordinates if we have them
        double lat = loc.getLatitude();
        double lng = loc.getLongitude();
        String fallback = String.format("현재 위치는 위도 %.5f°, 경도 %.5f° 근방입니다.", lat, lng);
        log.info("answerWhereAmI: falling back to coordinates for user {}", userId);
        return Optional.of(fallback);
    }

    /**
     * Detect a location oriented intent from the provided user query.  This
     * delegates to the {@link LocationIntentDetector} but adds a null
     * check and returns {@link LocationIntent#NONE} for blank queries.
     *
     * @param query the user query
     * @return the detected intent
     */
    public LocationIntent detectIntent(String query) {
        if (query == null || query.isBlank()) {
            return LocationIntent.NONE;
        }
        return intentDetector.detect(query);
    }
}