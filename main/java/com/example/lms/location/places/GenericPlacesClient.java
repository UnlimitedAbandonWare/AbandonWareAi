package com.example.lms.location.places;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class GenericPlacesClient implements PlacesClient {
    private static final Logger log = LoggerFactory.getLogger(GenericPlacesClient.class);
    private static final String DISABLED_REASON = "places provider is not configured";
    private static final String DISABLED_REASON_CODE = "places_provider_not_configured";

    @Override
    public List<Place> search(double lat, double lng, String categoryOrQuery, int limit) {
        traceProviderDisabled(limit);
        log.debug("[places.generic] accepted=false disabledReason={} requestedLimit={}",
                DISABLED_REASON, Math.max(0, limit));
        return Collections.emptyList();
    }

    private static void traceProviderDisabled(int limit) {
        TraceStore.put("location.places.providerDisabled", true);
        TraceStore.put("location.places.disabledReason", DISABLED_REASON_CODE);
        TraceStore.put("location.places.skipped.reason", DISABLED_REASON_CODE);
        TraceStore.put("location.places.requestedLimit", Math.max(0, limit));
    }
}
