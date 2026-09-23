package com.example.lms.location;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.location.geo.ReverseGeocodingClient;
import com.example.lms.location.intent.LocationIntentDetector;
import com.example.lms.location.domain.LastLocation;
import com.example.lms.location.domain.UserLocationConsent;
import com.example.lms.location.dto.LocationEventDto;
import com.example.lms.location.repo.LastLocationRepository;
import com.example.lms.location.repo.UserLocationConsentRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationServiceLogRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void answerWhereAmIDoesNotLogRawUserIdWhenConsentIsMissing() {
        UserLocationConsentRepository consentRepo = mock(UserLocationConsentRepository.class);
        LastLocationRepository locationRepo = mock(LastLocationRepository.class);
        LocationService service = new LocationService(
                consentRepo,
                locationRepo,
                mock(LocationIntentDetector.class),
                mock(ReverseGeocodingClient.class));
        String rawUserId = "user-location-secret-123";
        when(consentRepo.findByUserId(rawUserId)).thenReturn(Optional.empty());

        Logger logger = (Logger) LoggerFactory.getLogger(LocationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            assertTrue(service.answerWhereAmI(rawUserId).isEmpty());

            String rendered = appender.list.get(0).getFormattedMessage();
            assertFalse(rendered.contains(rawUserId));
            assertTrue(rendered.contains("userHash=" + SafeRedactor.hashValue(rawUserId)));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void saveEventReverseGeocodeFailureLeavesRedactedTraceBreadcrumb() {
        UserLocationConsentRepository consentRepo = mock(UserLocationConsentRepository.class);
        LastLocationRepository locationRepo = mock(LastLocationRepository.class);
        ReverseGeocodingClient geocoder = mock(ReverseGeocodingClient.class);
        LocationService service = new LocationService(
                consentRepo,
                locationRepo,
                mock(LocationIntentDetector.class),
                geocoder);
        String rawUserId = "user-location-secret-save";
        LocationEventDto event = new LocationEventDto(37.5, 127.0, 10.0f, 1L, "manual");
        when(consentRepo.findByUserId(rawUserId)).thenReturn(Optional.of(UserLocationConsent.of(rawUserId, true)));
        when(locationRepo.findByUserId(rawUserId)).thenReturn(Optional.empty());
        when(geocoder.reverse(37.5, 127.0)).thenThrow(new IllegalStateException("private geocode outage"));

        service.saveEvent(rawUserId, event);

        verify(locationRepo).save(org.mockito.ArgumentMatchers.any(LastLocation.class));
        assertEquals(true, TraceStore.get("location.suppressed.saveEvent.reverseGeocode"));
        assertEquals("IllegalStateException",
                TraceStore.get("location.suppressed.saveEvent.reverseGeocode.errorType"));
        assertEquals("saveEvent.reverseGeocode", TraceStore.get("location.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("location.suppressed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawUserId));
        assertFalse(TraceStore.getAll().toString().contains("private geocode outage"));
    }

    @Test
    void answerWhereAmIStaleAgeFailureLeavesRedactedTraceBreadcrumb() {
        UserLocationConsentRepository consentRepo = mock(UserLocationConsentRepository.class);
        LastLocationRepository locationRepo = mock(LastLocationRepository.class);
        ReverseGeocodingClient geocoder = mock(ReverseGeocodingClient.class);
        LocationService service = new LocationService(
                consentRepo,
                locationRepo,
                mock(LocationIntentDetector.class),
                geocoder);
        String rawUserId = "user-location-secret-age";
        LastLocation location = location(rawUserId, null);
        when(consentRepo.findByUserId(rawUserId)).thenReturn(Optional.of(UserLocationConsent.of(rawUserId, true)));
        when(locationRepo.findByUserId(rawUserId)).thenReturn(Optional.of(location));
        when(geocoder.reverse(location.getLatitude(), location.getLongitude())).thenReturn(Optional.empty());

        assertTrue(service.answerWhereAmI(rawUserId).isPresent());

        assertEquals(true, TraceStore.get("location.suppressed.answerWhereAmI.staleAge"));
        assertEquals("NullPointerException",
                TraceStore.get("location.suppressed.answerWhereAmI.staleAge.errorType"));
        assertEquals("answerWhereAmI.staleAge", TraceStore.get("location.suppressed.stage"));
        assertEquals("NullPointerException", TraceStore.get("location.suppressed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawUserId));
    }

    @Test
    void answerWhereAmIReverseGeocodeFailureLeavesRedactedTraceBreadcrumb() {
        UserLocationConsentRepository consentRepo = mock(UserLocationConsentRepository.class);
        LastLocationRepository locationRepo = mock(LastLocationRepository.class);
        ReverseGeocodingClient geocoder = mock(ReverseGeocodingClient.class);
        LocationService service = new LocationService(
                consentRepo,
                locationRepo,
                mock(LocationIntentDetector.class),
                geocoder);
        String rawUserId = "user-location-secret-answer";
        LastLocation location = location(rawUserId, Instant.now());
        when(consentRepo.findByUserId(rawUserId)).thenReturn(Optional.of(UserLocationConsent.of(rawUserId, true)));
        when(locationRepo.findByUserId(rawUserId)).thenReturn(Optional.of(location));
        when(geocoder.reverse(location.getLatitude(), location.getLongitude()))
                .thenThrow(new IllegalStateException("private geocode outage"));

        assertTrue(service.answerWhereAmI(rawUserId).isPresent());

        assertEquals(true, TraceStore.get("location.suppressed.answerWhereAmI.reverseGeocode"));
        assertEquals("IllegalStateException",
                TraceStore.get("location.suppressed.answerWhereAmI.reverseGeocode.errorType"));
        assertEquals("answerWhereAmI.reverseGeocode", TraceStore.get("location.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("location.suppressed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawUserId));
        assertFalse(TraceStore.getAll().toString().contains("private geocode outage"));
    }

    private static LastLocation location(String userId, Instant capturedAt) {
        LastLocation location = new LastLocation();
        location.setUserId(userId);
        location.setLatitude(37.5d);
        location.setLongitude(127.0d);
        location.setAccuracy(15.0f);
        location.setCapturedAt(capturedAt);
        return location;
    }
}
