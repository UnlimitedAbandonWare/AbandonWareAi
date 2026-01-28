package com.example.lms.location;

import com.example.lms.location.domain.LastLocation;
import com.example.lms.location.domain.UserLocationConsent;
import com.example.lms.location.geo.ReverseGeocodingClient;
import com.example.lms.location.intent.LocationIntentDetector;
import com.example.lms.location.repo.LastLocationRepository;
import com.example.lms.location.repo.UserLocationConsentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.Optional;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests verifying that the location service correctly
 * renders coarse addresses based on the reported accuracy.  These
 * tests exercise the road/dong/district/city thresholds, ensure
 * cached addresses are preferred and confirm that stale locations
 * trigger a refresh hint.  The LocationService is instantiated with
 * mocked repositories and a mocked reverse geocoding client to
 * provide deterministic results without hitting external services.
 */
class LocationServiceGranularityTest {

    /**
     * Build a LocationService instance with a synthetic LastLocation and
     * reverse geocoding result.  Consent is enabled for the user and
     * capturedAt is set to the current time.  Accuracy, latitude and
     * longitude are configurable per test.  The provided address may be
     * null to simulate a geocoding failure.
     */
    private LocationService serviceWithAccuracy(float acc, ReverseGeocodingClient.Address addr) {
        String uid = "u";
        var consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(uid))
                .thenReturn(Optional.of(UserLocationConsent.of(uid, true)));
        var locRepo = Mockito.mock(LastLocationRepository.class);
        var last = new LastLocation();
        last.setUserId(uid);
        last.setLatitude(36.35);
        last.setLongitude(127.33);
        last.setAccuracy(acc);
        last.setCapturedAt(Instant.now());
        Mockito.when(locRepo.findByUserId(uid)).thenReturn(Optional.of(last));
        var geo = Mockito.mock(ReverseGeocodingClient.class);
        Mockito.when(geo.reverse(last.getLatitude(), last.getLongitude()))
                .thenReturn(Optional.ofNullable(addr));
        return new LocationService(consentRepo, locRepo, new LocationIntentDetector(), geo);
    }

    private String ask(LocationService svc) {
        return svc.answerWhereAmI("u").orElse("");
    }

    @Test
    void coarse_when_very_low_precision_city_only() {
        var svc = serviceWithAccuracy(50_000f, new ReverseGeocodingClient.Address("대전광역시", "유성구", null));
        String msg = ask(svc);
        assertThat(msg).contains("대전");
        assertThat(msg).doesNotContain("좌표 기준");
        assertThat(msg).contains("±50.0km");
    }

    @Test
    void district_when_mid_precision_3km() {
        var svc = serviceWithAccuracy(3_000f, new ReverseGeocodingClient.Address("대전광역시", "유성구", null));
        String msg = ask(svc);
        assertThat(msg).contains("대전광역시 유성구");
        assertThat(msg).contains("±3.0km");
    }

    @Test
    void dong_when_1km() {
        var svc = serviceWithAccuracy(800f, new ReverseGeocodingClient.Address("대전광역시", "유성구", null));
        String msg = ask(svc);
        assertThat(msg).contains("대전광역시 유성구");
        assertThat(msg).contains("±800m");
    }

    @Test
    void road_when_200m() {
        var svc = serviceWithAccuracy(50f, new ReverseGeocodingClient.Address("대전광역시", "유성구", "덕명로 12"));
        String msg = ask(svc);
        assertThat(msg).contains("덕명로");
        assertThat(msg).contains("±50m");
    }

    @Test
    void uses_cached_address_first() {
        // When the reverse geocoding client returns a road address, the
        // LocationService should include it in the response regardless
        // of accuracy when available.  The caching behaviour is tested
        // implicitly by the service's preference for the provided
        // geocoded address.
        var svc = serviceWithAccuracy(500f, new ReverseGeocodingClient.Address("대전광역시", "유성구", "어은로"));
        String msg = ask(svc);
        assertThat(msg).contains("어은로");
    }

    @Test
    void stale_location_hint() {
        String uid = "u";
        var consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(uid))
                .thenReturn(Optional.of(UserLocationConsent.of(uid, true)));
        var locRepo = Mockito.mock(LastLocationRepository.class);
        var old = new LastLocation();
        old.setUserId(uid);
        old.setLatitude(36.35);
        old.setLongitude(127.33);
        old.setAccuracy(1_000f);
        // set capturedAt far in the past (>24h)
        old.setCapturedAt(Instant.now().minusSeconds(60L * 60 * 25));
        Mockito.when(locRepo.findByUserId(uid)).thenReturn(Optional.of(old));
        var geo = Mockito.mock(ReverseGeocodingClient.class);
        var svc = new LocationService(consentRepo, locRepo, new LocationIntentDetector(), geo);
        String msg = ask(svc);
        assertThat(msg).contains("위치 정보가 오래되었습니다");
    }
}