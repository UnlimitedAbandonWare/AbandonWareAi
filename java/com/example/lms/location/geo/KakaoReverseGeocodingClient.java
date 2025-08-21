package com.example.lms.location.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * Kakao implementation of the {@link ReverseGeocodingClient}.  This
 * adapter invokes Kakao's coordinate→address REST endpoint and
 * extracts the administrative region and road name from the response.
 * Requests are authenticated via the {@code kakao.rest-key} property
 * supplied in {@code application.yml}.  When no key is configured or
 * an error occurs during the HTTP call or JSON parsing, the
 * implementation returns an empty {@link Optional}.
 */
@Slf4j
@Component
public class KakaoReverseGeocodingClient implements ReverseGeocodingClient {

    /** Kakao REST API host for reverse geocoding */
    private static final String BASE_URL = "https://dapi.kakao.com";
    /** Path for the coordinate to address lookup */
    private static final String REVERSE_PATH = "/v2/local/geo/coord2address.json";

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public KakaoReverseGeocodingClient(WebClient.Builder builder,
                                       @Value("${kakao.rest-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = builder
                .baseUrl(BASE_URL)
                .build();
    }

    @Override
    public Optional<Address> reverse(double lat, double lng) {
        // If no API key is configured bail early.  Fail‑soft behaviour
        // ensures downstream callers fall back to coordinate messages.
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Kakao REST key is not configured; skipping reverse geocoding");
            return Optional.empty();
        }
        try {
            Mono<String> call = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(REVERSE_PATH)
                            .queryParam("x", lng)
                            .queryParam("y", lat)
                            .queryParam("input_coord", "WGS84")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3));

            String json = call.block();
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(json);
            JsonNode docs = root.path("documents");
            if (!docs.isArray() || docs.isEmpty()) {
                return Optional.empty();
            }
            JsonNode doc = docs.get(0);
            // administrative region (region_1depth_name / region_2depth_name)
            String city = null;
            String district = null;
            String road = null;
            JsonNode addressNode = doc.path("address");
            if (!addressNode.isMissingNode()) {
                city = textOrNull(addressNode.path("region_1depth_name"));
                district = textOrNull(addressNode.path("region_2depth_name"));
            }
            JsonNode roadNode = doc.path("road_address");
            if (!roadNode.isMissingNode()) {
                // full road address; may be blank
                road = textOrNull(roadNode.path("address_name"));
            }
            if ((city == null || city.isBlank()) && (district == null || district.isBlank())) {
                // Unable to extract meaningful administrative info
                return Optional.empty();
            }
            return Optional.of(new Address(city, district, road));
        } catch (Exception e) {
            // Catch all parsing and connectivity errors and log at debug
            log.debug("Reverse geocoding failed", e);
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && !node.isMissingNode() && !node.isNull()) ? node.asText() : null;
    }
}