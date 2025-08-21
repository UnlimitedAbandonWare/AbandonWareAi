package com.example.lms.location.places;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;

/**
 * Stub implementation of {@link PlacesClient} that integrates with the
 * Kakao Local Search API.  The actual HTTP call is not implemented in
 * this reference to avoid leaking API keys and to keep the build self
 * contained.  When the kakao.rest-key property is provided the search
 * method should call the Kakao REST endpoint and map the results to
 * {@link Place} objects.
 */
@Service
public class KakaoPlacesClient implements PlacesClient {

    private final WebClient kakaoWebClient;
    private final String restKey;

    public KakaoPlacesClient(WebClient.Builder builder,
                             @Value("${kakao.rest-key:}") String restKey) {
        this.kakaoWebClient = builder.baseUrl("https://dapi.kakao.com").build();
        this.restKey = restKey;
    }

    @Override
    public List<Place> search(double lat, double lng, String categoryOrQuery, int limit) {
        // If no API key is configured return an empty result.  Replace this
        // block with an actual REST call when a valid key is available.
        if (restKey == null || restKey.isBlank()) {
            return Collections.emptyList();
        }
        // TODO: Implement Kakao Local Search call using WebClient when keys are provided.
        // For now return an empty list to satisfy callers.
        return Collections.emptyList();
    }
}