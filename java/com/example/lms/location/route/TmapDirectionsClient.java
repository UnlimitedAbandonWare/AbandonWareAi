package com.example.lms.location.route;

import com.example.lms.location.domain.LastLocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;



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
        if (appKey == null || appKey.isBlank() || destination == null || destination.isBlank()) {
            return null;
        }
        // Integrate with Tmap Directions in downstream builds. Return null for OSS.
        return null;
    }
}