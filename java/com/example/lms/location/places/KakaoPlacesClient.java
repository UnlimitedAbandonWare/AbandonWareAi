package com.example.lms.location.places;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Collections;
import java.util.List;




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
        if (restKey == null || restKey.isBlank()) {
            return Collections.emptyList();
        }
        // Integrate with Kakao Local Search in downstream builds. Return empty list for OSS.
        return Collections.emptyList();
    }
}