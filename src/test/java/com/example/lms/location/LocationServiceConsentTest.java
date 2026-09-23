package com.example.lms.location;

import com.example.lms.location.domain.UserLocationConsent;
import com.example.lms.location.dto.LocationEventDto;
import com.example.lms.location.geo.ReverseGeocodingClient;
import com.example.lms.location.intent.LocationIntentDetector;
import com.example.lms.location.repo.LastLocationRepository;
import com.example.lms.location.repo.UserLocationConsentRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationServiceConsentTest {

    @Test
    void revokingConsentPurgesTheResolvedAddressCache() {
        String userId = "location-consent-test-user";
        UserLocationConsent consent = UserLocationConsent.of(userId, true);
        UserLocationConsentRepository consentRepo = mock(UserLocationConsentRepository.class);
        LastLocationRepository locationRepo = mock(LastLocationRepository.class);
        ReverseGeocodingClient geocoder = mock(ReverseGeocodingClient.class);
        when(consentRepo.findByUserId(userId)).thenReturn(Optional.of(consent));
        when(locationRepo.findByUserId(userId)).thenReturn(Optional.empty());
        when(locationRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(consentRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(geocoder.reverse(37.5665, 126.9780)).thenReturn(Optional.of(
                new ReverseGeocodingClient.Address("Seoul", "Jung-gu", "Sejong-daero")));
        LocationService service = new LocationService(
                consentRepo,
                locationRepo,
                mock(LocationIntentDetector.class),
                geocoder);

        service.saveEvent(userId, new LocationEventDto(
                37.5665,
                126.9780,
                25.0f,
                1710000000000L,
                "manual"));
        assertTrue(service.getResolvedAddress(userId).isPresent());

        service.setConsent(userId, false);

        assertFalse(service.getResolvedAddress(userId).isPresent());
    }
}
