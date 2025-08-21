package com.example.lms.location.route;

import com.example.lms.location.domain.LastLocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Stub implementation of {@link DirectionsClient} that integrates with the
 * Tmap Directions API.  The actual HTTP call is not implemented in this
 * reference to keep the build self contained and to avoid leaking API keys.
 * When the {@code tmap.app-key} property is provided the {@link #eta}
 * method should perform a REST call to Tmap's routing endpoint and map
 * the response into an {@link EtaResult}.
 */
@Service
public class TmapDirectionsClient implements DirectionsClient {

    private final WebClient tmapWebClient;
    private final String appKey;

    public TmapDirectionsClient(WebClient.Builder builder,
                                @Value("${tmap.app-key:}") String appKey) {
        this.tmapWebClient = builder.baseUrl("https://apis.openapi.sk.com").build();
        this.appKey = appKey;
    }

    @Override
    public EtaResult eta(LastLocation from, String destination) {
        // If no API key is configured or the destination is blank, return null.
        if (appKey == null || appKey.isBlank() || destination == null || destination.isBlank()) {
            return null;
        }
        // TODO: Implement Tmap Directions API call using WebClient when an API key is provided.
        // For now return null to indicate that routing information could not be computed.
        return null;
    }
}