package com.example.lms.location;

import com.example.lms.location.domain.LastLocation;
import com.example.lms.location.domain.UserLocationConsent;
import com.example.lms.location.intent.LocationIntentDetector;
import com.example.lms.location.geo.ReverseGeocodingClient;
import com.example.lms.location.places.PlacesClient;
import com.example.lms.location.route.DirectionsClient;
import com.example.lms.location.repo.LastLocationRepository;
import com.example.lms.location.repo.UserLocationConsentRepository;
import com.example.lms.config.LocationFeatureProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.List;
import java.util.Optional;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test verifying that the {@link LocationService#nearbyPharmacies(String,int)}
 * method renders a bullet list of nearby pharmacies when consent is enabled
 * and a recent location is available.  Mocked dependencies are used to
 * supply a fixed location and two pharmacy results.
 */
public class NearbyPharmaciesTest {

    @Test
    void nearbyPharmacies_returnsList() throws Exception {
        String userId = "u1";
        // Prepare a recent last location for the user
        LastLocation loc = new LastLocation();
        loc.setUserId(userId);
        loc.setLatitude(36.0);
        loc.setLongitude(127.0);
        loc.setAccuracy(5.0f);
        loc.setCapturedAt(Instant.now());

        UserLocationConsent consent = UserLocationConsent.of(userId, true);
        UserLocationConsentRepository consentRepo = Mockito.mock(UserLocationConsentRepository.class);
        Mockito.when(consentRepo.findByUserId(userId)).thenReturn(Optional.of(consent));
        LastLocationRepository locRepo = Mockito.mock(LastLocationRepository.class);
        Mockito.when(locRepo.findByUserId(userId)).thenReturn(Optional.of(loc));

        LocationIntentDetector det = new LocationIntentDetector();
        ReverseGeocodingClient rev = Mockito.mock(ReverseGeocodingClient.class);
        // Prepare two pharmacy results
        PlacesClient.Place p1 = new PlacesClient.Place("약국A", "서울시", "서울시", 36.001, 127.001, 100.0, null, null, "약국");
        PlacesClient.Place p2 = new PlacesClient.Place("약국B", "서울시", "서울시", 36.002, 127.002, 200.0, null, null, "약국");
        PlacesClient places = Mockito.mock(PlacesClient.class);
        Mockito.when(places.searchPharmacies(Mockito.anyDouble(), Mockito.anyDouble(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(List.of(p1, p2));
        DirectionsClient directions = Mockito.mock(DirectionsClient.class);
        LocationFeatureProperties props = new LocationFeatureProperties();
        props.setMaxPharmacies(5);

        // Construct service with required dependencies
        LocationService service = new LocationService(consentRepo, locRepo, det, rev);
        // Inject optional dependencies via reflection
        java.lang.reflect.Field f1 = LocationService.class.getDeclaredField("placesClient");
        f1.setAccessible(true);
        f1.set(service, places);
        java.lang.reflect.Field f2 = LocationService.class.getDeclaredField("directionsClient");
        f2.setAccessible(true);
        f2.set(service, directions);
        java.lang.reflect.Field f3 = LocationService.class.getDeclaredField("locationFeatureProperties");
        f3.setAccessible(true);
        f3.set(service, props);

        Optional<String> opt = service.nearbyPharmacies(userId, 0);
        assertThat(opt).isPresent();
        String msg = opt.get();
        assertThat(msg).contains("약국A");
        assertThat(msg).contains("약국B");
    }
}