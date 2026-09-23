package com.example.lms.location;

import com.example.lms.location.geo.GenericReverseGeocodingClient;
import com.example.lms.location.places.GenericPlacesClient;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericLocationProviderTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void genericPlacesClientLeavesProviderDisabledTraceWithoutRawQueryOrCoordinates() {
        var results = new GenericPlacesClient().search(37.5665d, 126.9780d, "private pharmacy query", 3);

        assertTrue(results.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("location.places.providerDisabled"));
        assertEquals("places_provider_not_configured", TraceStore.get("location.places.disabledReason"));
        assertEquals("places_provider_not_configured", TraceStore.get("location.places.skipped.reason"));
        assertEquals(3, TraceStore.get("location.places.requestedLimit"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private pharmacy query"));
        assertFalse(trace.contains("37.5665"));
        assertFalse(trace.contains("126.978"));
    }

    @Test
    void genericReverseGeocoderLeavesProviderDisabledTraceWithoutRawCoordinates() {
        var result = new GenericReverseGeocodingClient().reverse(37.5665d, 126.9780d);

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("location.reverseGeocode.providerDisabled"));
        assertEquals("reverse_geocoding_provider_not_configured",
                TraceStore.get("location.reverseGeocode.disabledReason"));
        assertEquals("reverse_geocoding_provider_not_configured",
                TraceStore.get("location.reverseGeocode.skipped.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("37.5665"));
        assertFalse(trace.contains("126.978"));
    }
}
