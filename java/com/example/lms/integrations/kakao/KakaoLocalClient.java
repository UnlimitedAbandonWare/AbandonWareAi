
        package com.example.lms.integrations.kakao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper over the Kakao Local API providing convenience methods to
 * perform keyword and category searches and to reverse geocode coordinates.
 *
 * <p>This client is designed for use in backend services and does not
 * expose lower‑level networking concerns to callers.  It handles
 * construction of URIs, inclusion of the required authorization header and
 * conversion of JSON responses into simple POJOs.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLocalClient {
    // Inject properties directly instead of relying on the generic KakaoProperties which is unrelated to Local API
    @Value("${kakao.rest-api-key}")
    private String restApiKey;

    @Value("${kakao.local.base-url:https://dapi.kakao.com}")
    private String baseUrl;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                .build();
    }

    /**
     * Perform a category search around the specified coordinate.  Results are
     * sorted by distance ascending by default.  If an exception occurs
     * during the HTTP call or JSON parsing, an empty list is returned and
     * a warning logged.
     *
     * @param lat     latitude in degrees
     * @param lon     longitude in degrees
     * @param groupCode the category group code (e.g. "FD6" for restaurants,
     * "CE7" for cafés)
     * @param radius  search radius in metres
     * @param size    maximum number of results to return
     * @return a list of {@link Place} instances representing nearby places
     */
    public List<Place> searchCategory(double lat, double lon, String groupCode, int radius, int size) {
        try {
            String uri = UriComponentsBuilder.fromPath("/v2/local/search/category.json")
                    .queryParam("category_group_code", groupCode)
                    .queryParam("x", lon)
                    .queryParam("y", lat)
                    .queryParam("radius", radius)
                    .queryParam("size", size)
                    .queryParam("sort", "distance")
                    .build().toUriString();
            Map<?, ?> body = client().get().uri(uri).retrieve().bodyToMono(Map.class).block();
            return mapToPlaces(body);
        } catch (Exception e) {
            log.warn("[KakaoLocal] category search failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Perform a keyword search around the specified coordinate.  Results are
     * sorted by distance ascending by default.
     *
     * @param lat    latitude in degrees
     * @param lon    longitude in degrees
     * @param query  search keyword
     * @param radius search radius in metres
     * @param size   maximum number of results to return
     * @return list of nearby places matching the keyword
     */
    public List<Place> searchKeyword(double lat, double lon, String query, int radius, int size) {
        try {
            String uri = UriComponentsBuilder.fromPath("/v2/local/search/keyword.json")
                    .queryParam("query", query)
                    .queryParam("x", lon)
                    .queryParam("y", lat)
                    .queryParam("radius", radius)
                    .queryParam("size", size)
                    .queryParam("sort", "distance")
                    .build().toUriString();
            Map<?, ?> body = client().get().uri(uri).retrieve().bodyToMono(Map.class).block();
            return mapToPlaces(body);
        } catch (Exception e) {
            log.warn("[KakaoLocal] keyword search failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Convert WGS84 coordinates to the corresponding administrative region name.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return optional region name (e.g. "서울특별시 강남구 역삼동")
     */
    @SuppressWarnings("unchecked")
    public Optional<String> reverseRegion(double lat, double lon) {
        try {
            String uri = UriComponentsBuilder.fromPath("/v2/local/geo/coord2regioncode.json")
                    .queryParam("x", lon)
                    .queryParam("y", lat)
                    .build().toUriString();
            Map<?, ?> body = client().get().uri(uri).retrieve().bodyToMono(Map.class).block();

            // ✅ [수정된 부분] 안전한 타입 변환 적용
            Object rawDocs = body.get("documents");
            List<Map<String, Object>> docs;
            if (rawDocs instanceof java.util.List<?> list) {
                docs = new java.util.ArrayList<>(list.size());
                for (Object o : list) {
                    if (o instanceof java.util.Map<?, ?> m) {
                        docs.add((Map<String, Object>) m);
                    }
                }
            } else {
                docs = java.util.Collections.emptyList();
            }

            if (docs.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> d = docs.get(0);
            List<String> parts = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                String key = "region_" + i + "depth_name";
                Object val = d.get(key);
                if (val instanceof String s && !s.isBlank()) {
                    parts.add(s);
                }
            }
            return Optional.of(String.join(" ", parts));
        } catch (Exception e) {
            log.warn("[KakaoLocal] reverse region failed: {}", e.toString());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Place> mapToPlaces(Map<?,?> body) {
        // ✅ [수정된 부분] 안전한 타입 변환 적용
        Object rawDocs = body.get("documents");
        List<Map<String, Object>> docs;
        if (rawDocs instanceof java.util.List<?> list) {
            docs = new java.util.ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) {
                    docs.add((Map<String, Object>) m);
                }
            }
        } else {
            docs = java.util.Collections.emptyList();
        }

        List<Place> out = new ArrayList<>();
        for (Map<String, Object> m : docs) {
            out.add(new Place(
                    (String) m.get("id"),
                    (String) m.get("place_name"),
                    (String) m.getOrDefault("road_address_name", (String) m.get("address_name")),
                    (String) m.get("phone"),
                    parseD((String) m.get("y")),
                    parseD((String) m.get("x")),
                    (String) m.get("place_url"),
                    (String) m.getOrDefault("distance", "")
            ));
        }
        return out;
    }

    private static double parseD(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Minimal description of a Kakao place.  Only the fields relevant to
     * downstream consumption are retained here.
     *
     * @param id       unique identifier
     * @param name     place name
     * @param address  road address (or general address when road address is absent)
     * @param phone    telephone number
     * @param lat      latitude in degrees
     * @param lon      longitude in degrees
     * @param url      Kakao map URL for the place
     * @param distanceMeters string representing the distance from the search point in metres
     */
    public record Place(String id, String name, String address, String phone,
                        double lat, double lon, String url, String distanceMeters) { }
}