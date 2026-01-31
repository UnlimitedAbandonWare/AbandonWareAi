package com.example.lms.location;

import com.example.lms.location.domain.LastLocation;
import com.example.lms.location.domain.UserLocationConsent;
import com.example.lms.location.intent.LocationIntentDetector;
import com.example.lms.location.geo.ReverseGeocodingClient;
import com.example.lms.location.places.PlacesClient;
import com.example.lms.location.route.DirectionsClient;
import com.example.lms.location.repo.LastLocationRepository;
import com.example.lms.location.repo.UserLocationConsentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.List;
import java.util.Optional;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test verifying that {@link LocationService#travelTimeToOffice(String,String)}
 * produces a formatted estimate given mocked direction and place results.  The
 * service should call the directions client when a destination coordinate is
 * available and render a Korean message indicating the mode and approximate
 * time.
 */
public class TravelTimeToOfficeTest {

    @Test
    void travelTime_estimation() throws Exception {
        String userId = "u2";
        LastLocation loc = new LastLocation();
        loc.setUserId(userId);
        loc.setLatitude(35.0);
        loc.setLongitude(128.0);
        loc.setAccuracy(10.0f);
        loc.setCapturedAt(Instant.now());
        UserLocationConsent consent = UserLocationConsent.of(userId, true);
        UserLocationConsentRepository consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(userId)).thenReturn(Optional.of(consent));
        LastLocationRepository locRepo = Mockito.mock(LastLocationRepository.class);
        Mockito.when(locRepo.findByUserId(userId)).thenReturn(Optional.of(loc));
        LocationIntentDetector det = new LocationIntentDetector();
        ReverseGeocodingClient rev = Mockito.mock(ReverseGeocodingClient.class);
        // Mock places client to return a fake destination coordinate
        PlacesClient.Place dest = new PlacesClient.Place("시청", "", "", 35.01, 128.02, 500.0, null, null, "관공서");
        PlacesClient places = Mockito.mock(PlacesClient.class);
        Mockito.when(places.searchKeyword(Mockito.anyString(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(List.of(dest));
        // Mock directions client to return a fixed ETA
        DirectionsClient.EtaResult eta = new DirectionsClient.EtaResult(500.0, 600.0, "walk", "Haversine", true, null);
        DirectionsClient directions = Mockito.mock(DirectionsClient.class);
        Mockito.when(directions.eta(Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyDouble(), Mockito.eq("walk")))
                .thenReturn(eta);
        LocationService service = new LocationService(consentRepo, locRepo, det, rev);
        // inject optional fields
        java.lang.reflect.Field f1 = LocationService.class.getDeclaredField("placesClient");
        f1.setAccessible(true);
        f1.set(service, places);
        java.lang.reflect.Field f2 = LocationService.class.getDeclaredField("directionsClient");
        f2.setAccessible(true);
        f2.set(service, directions);
        java.lang.reflect.Field f3 = LocationService.class.getDeclaredField("locationFeatureProperties");
        f3.setAccessible(true);
        f3.set(service, new com.example.lms.config.LocationFeatureProperties());
        Optional<String> opt = service.travelTimeToOffice(userId, "시청까지 얼마나 걸려?");
        assertThat(opt).isPresent();
        String msg = opt.get();
        // Should mention walking (도보) and minutes
        assertThat(msg).contains("도보");
        assertThat(msg).contains("분");
    }
}