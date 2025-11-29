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
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




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
/* Removed duplicate manual Logger 'log'; using Lombok @Slf4j provided 'log'. */
    private final UserLocationConsentRepository consentRepo;
    private final LastLocationRepository locationRepo;
    private final LocationIntentDetector intentDetector;
    /**
     * Reverse geocoding client used to convert coordinates into
     * human readable addresses.  May be a no-op implementation when
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
     * Granularity thresholds controlling how coarse the returned address should
     * be depending on the reported GPS accuracy.  These values are
     * configurable via the {@code location.thresholds.*} properties.  When
     * the accuracy is less than or equal to {@code roadThreshold} the
     * service will attempt to return a road level address (when available).
     * When greater than {@code roadThreshold} but less than or equal to
     * {@code dongThreshold} a city + dong (neighbourhood) address is
     * returned.  When greater than {@code dongThreshold} but less than or
     * equal to {@code districtThreshold} a city + district (gu/군) address
     * is returned.  Accuracy beyond {@code districtThreshold} results in a
     * city level address.
     */
    @org.springframework.beans.factory.annotation.Value("${location.thresholds.road-m:200}")
    private float roadThreshold;
    @org.springframework.beans.factory.annotation.Value("${location.thresholds.dong-m:1000}")
    private float dongThreshold;
    @org.springframework.beans.factory.annotation.Value("${location.thresholds.district-m:5000}")
    private float districtThreshold;

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
        // Stale check: if the location is older than 24 hours advise refresh
        try {
            java.time.Duration age = java.time.Duration.between(loc.getCapturedAt(), java.time.Instant.now());
            if (age.toHours() >= 24) {
                log.info("answerWhereAmI: stale location ({}h); advising refresh", age.toHours());
                return Optional.of("최근 위치 정보가 오래되었습니다(약 " + age.toHours() + "시간 전). 우측 상단의 위치 버튼을 눌러 갱신해 주세요.");
            }
        } catch (Exception ignore) {
            // fail softly when capturedAt is null or invalid
        }
        float acc = Math.max(0f, loc.getAccuracy());
        double lat = loc.getLatitude();
        double lng = loc.getLongitude();
        // Log low precision cases to aid debugging.  When the uncertainty
        // exceeds the district threshold the returned address will be very
        // coarse.
        if (acc >= districtThreshold) {
            log.info("answerWhereAmI: low GPS precision (uncertainty={}m) → coarse address", acc);
        }
        // 1) Prefer cached address resolved during saveEvent.  This avoids
        // hitting the geocoding service when the user has already sent a
        // location event.
        var cached = getResolvedAddress(userId);
        if (cached.isPresent()) {
            return Optional.of(renderCoarse(cached.get(), acc, lat, lng));
        }
        // 2) Attempt reverse geocoding regardless of accuracy.  Fail
        // softly on exceptions.
        try {
            var addrOpt = reverseGeocodingClient.reverse(lat, lng);
            if (addrOpt.isPresent()) {
                return Optional.of(renderCoarse(addrOpt.get(), acc, lat, lng));
            }
        } catch (Exception ignore) {
            // swallow all exceptions; we will fall back to coordinates
        }
        // 3) Fallback to coordinates only message when no address could be
        // resolved.
        return Optional.of(renderCoordinates(lat, lng, acc));
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

    /**
     * Assemble a human friendly message from a resolved address and the
     * reported accuracy.  The granularity of the displayed address is
     * determined by the configured thresholds: road, dong and district.
     * When the accuracy is within the road threshold and a road address
     * exists it will be displayed.  When the accuracy is between the road
     * and dong thresholds the service returns a city + dong (neighbourhood)
     * address when available.  Between the dong and district thresholds the
     * service returns a city + district address.  Beyond the district
     * threshold only the city is returned.  A precision hint and a Google
     * Maps link are always included.
     *
     * @param a   the resolved address from the geocoding provider
     * @param acc the reported accuracy in meters
     * @param lat the latitude used for the lookup
     * @param lng the longitude used for the lookup
     * @return a formatted string for end users
     */
    private String renderCoarse(com.example.lms.location.geo.ReverseGeocodingClient.Address a,
                                float acc, double lat, double lng) {
        String display;
        if (acc <= roadThreshold && a.road() != null && !a.road().isBlank()) {
            display = a.road();
        } else if (acc <= dongThreshold && a.district() != null && !a.district().isBlank()) {
            display = (a.city() != null && !a.city().isBlank())
                    ? (a.city() + " " + a.district())
                    : a.district();
        } else if (acc <= districtThreshold && a.district() != null && !a.district().isBlank()) {
            display = (a.city() != null && !a.city().isBlank())
                    ? (a.city() + " " + a.district())
                    : a.district();
        } else {
            if (a.city() != null && !a.city().isBlank()) {
                display = a.city();
            } else if (a.district() != null && !a.district().isBlank()) {
                display = a.district();
            } else {
                display = "알 수 없는 지역";
            }
        }
        return renderHuman(display, lat, lng, acc);
    }

    /**
     * Format a fallback message using only coordinates.  When no address is
     * available this method produces a generic location hint with a
     * precision indicator and a Google Maps link.
     *
     * @param lat latitude in decimal degrees
     * @param lng longitude in decimal degrees
     * @param acc accuracy in meters
     * @return a human friendly message containing only coordinates
     */
    private String renderCoordinates(double lat, double lng, float acc) {
        return renderHuman(null, lat, lng, acc);
    }

    /**
     * Compose the final user facing string given an optional display name
     * (road, dong, district or city), the raw coordinates and the accuracy.
     * When the display name is null or blank a generic coordinate hint is
     * returned.  Otherwise the display name is included along with the
     * precision hint and a Google Maps link.
     *
     * @param display the human readable address fragment (may be null)
     * @param lat     latitude in decimal degrees
     * @param lng     longitude in decimal degrees
     * @param acc     accuracy in meters
     * @return a formatted string for the chat interface
     */
    private String renderHuman(String display, double lat, double lng, float acc) {
        String prec = (acc >= 1000f)
                ? String.format("±%.1fkm", acc / 1000f)
                : String.format("±%.0fm", acc);
        String map = "https://maps.google.com/?q=" + lat + "," + lng;
        if (display == null || display.isBlank()) {
            return String.format(
                    "📍 좌표 기준 추정 위치 %s (좌표: %.5f°, %.5f° · 지도: %s)",
                    prec, lat, lng, map
            );
        }
        return String.format(
                "📍 %s (%s)\n↳ 좌표: %.5f°, %.5f° · 지도: %s",
                display, prec, lat, lng, map
        );
    }
}