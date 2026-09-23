package com.example.lms.location.geo;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class GenericReverseGeocodingClient implements ReverseGeocodingClient {
    private static final Logger log = LoggerFactory.getLogger(GenericReverseGeocodingClient.class);
    private static final String DISABLED_REASON = "reverse geocoding provider is not configured";
    private static final String DISABLED_REASON_CODE = "reverse_geocoding_provider_not_configured";

    @Override
    public Optional<Address> reverse(double lat, double lng) {
        traceProviderDisabled();
        log.debug("[geo.reverse.generic] accepted=false disabledReason={}", DISABLED_REASON);
        return Optional.empty();
    }

    private static void traceProviderDisabled() {
        TraceStore.put("location.reverseGeocode.providerDisabled", true);
        TraceStore.put("location.reverseGeocode.disabledReason", DISABLED_REASON_CODE);
        TraceStore.put("location.reverseGeocode.skipped.reason", DISABLED_REASON_CODE);
    }
}
