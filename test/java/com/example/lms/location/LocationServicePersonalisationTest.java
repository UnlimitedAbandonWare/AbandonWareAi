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
 * Unit tests for the personalised location response logic in
 * {@link LocationService#answerWhereAmI(String)}.  These tests
 * exercise the consent checks, coordinate retrieval and reverse
 * geocoding logic in isolation using Mockito mocks for the
 * repositories and client.
 */
class LocationServicePersonalisationTest {

    @Test
    void answerWhereAmI_withConsentAndAddress_returnsAddressMessage() {
        // Arrange: user has consent enabled and a recent location
        String userId = "u1";
        UserLocationConsent consent = UserLocationConsent.of(userId, true);

        LastLocation loc = new LastLocation();
        loc.setUserId(userId);
        loc.setLatitude(37.56);
        loc.setLongitude(126.97);
        loc.setAccuracy(5.0f);
        loc.setCapturedAt(Instant.now());

        UserLocationConsentRepository consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(userId)).thenReturn(Optional.of(consent));

        LastLocationRepository locationRepo = Mockito.mock(LastLocationRepository.class);
        Mockito.when(locationRepo.findByUserId(userId)).thenReturn(Optional.of(loc));

        ReverseGeocodingClient fakeGeo = Mockito.mock(ReverseGeocodingClient.class);
        ReverseGeocodingClient.Address addr = new ReverseGeocodingClient.Address("서울특별시", "종로구", "세종대로 175");
        Mockito.when(fakeGeo.reverse(loc.getLatitude(), loc.getLongitude())).thenReturn(Optional.of(addr));

        LocationIntentDetector intentDetector = new LocationIntentDetector();
        LocationService service = new LocationService(consentRepo, locationRepo, intentDetector, fakeGeo);

        // Act
        Optional<String> opt = service.answerWhereAmI(userId);

        // Assert
        assertThat(opt).isPresent();
        assertThat(opt.get()).contains("세종대로");
    }

    @Test
    void answerWhereAmI_reverseGeocodeFails_fallsBackToCoordinates() {
        String userId = "u2";
        UserLocationConsent consent = UserLocationConsent.of(userId, true);
        LastLocation loc = new LastLocation();
        loc.setUserId(userId);
        loc.setLatitude(35.0);
        loc.setLongitude(128.0);
        loc.setAccuracy(5.0f);
        loc.setCapturedAt(Instant.now());

        UserLocationConsentRepository consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(userId)).thenReturn(Optional.of(consent));
        LastLocationRepository locationRepo = Mockito.mock(LastLocationRepository.class);
        Mockito.when(locationRepo.findByUserId(userId)).thenReturn(Optional.of(loc));
        ReverseGeocodingClient fakeGeo = Mockito.mock(ReverseGeocodingClient.class);
        Mockito.when(fakeGeo.reverse(loc.getLatitude(), loc.getLongitude())).thenReturn(Optional.empty());
        LocationService service = new LocationService(consentRepo, locationRepo, new LocationIntentDetector(), fakeGeo);
        Optional<String> opt = service.answerWhereAmI(userId);
        assertThat(opt).isPresent();
        assertThat(opt.get()).contains("위도");
        assertThat(opt.get()).contains("경도");
    }

    @Test
    void answerWhereAmI_noConsent_returnsEmpty() {
        String userId = "u3";
        UserLocationConsentRepository consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(userId)).thenReturn(Optional.of(UserLocationConsent.of(userId, false)));
        LastLocationRepository locationRepo = Mockito.mock(LastLocationRepository.class);
        ReverseGeocodingClient fakeGeo = Mockito.mock(ReverseGeocodingClient.class);
        LocationService service = new LocationService(consentRepo, locationRepo, new LocationIntentDetector(), fakeGeo);
        Optional<String> opt = service.answerWhereAmI(userId);
        assertThat(opt).isEmpty();
    }
}