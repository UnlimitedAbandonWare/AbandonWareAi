package com.example.lms.location.api;

import com.example.lms.location.LocationService;
import com.example.lms.location.dto.LocationEventDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationControllerTest {

    @Test
    void setConsentReturnsStableErrorCodeWhenUserIdentifierIsMissing() {
        LocationController controller = new LocationController(mock(LocationService.class));

        ResponseEntity<?> response = controller.setConsent("on", null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("missing_user_identifier", body.get("error"));
    }

    @Test
    void ingestDoesNotEchoRawUserIdentifierWhenConsentIsDisabled() {
        LocationService service = mock(LocationService.class);
        LocationController controller = new LocationController(service);
        String rawUserId = "raw-location-user-secret-123";
        when(service.isEnabled(rawUserId)).thenReturn(false);

        ResponseEntity<?> response = controller.ingest(
                new LocationEventDto(37.5665, 126.9780, 25.0f, 1710000000000L, "manual"),
                null,
                rawUserId);

        assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("location_consent_disabled", body.get("error"));
        assertFalse(body.toString().contains(rawUserId));
    }
}
