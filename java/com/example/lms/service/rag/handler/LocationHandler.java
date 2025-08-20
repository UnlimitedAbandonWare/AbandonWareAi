package com.example.lms.service.rag.handler;

import com.example.lms.integrations.kakao.KakaoLocalClient;
import com.example.lms.location.LocationMemory;
import com.example.lms.location.WeatherService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Retrieval handler that inspects the user query for location‑based intents
 * (e.g. nearby restaurants, cafes or current weather).  When a match is
 * detected and a location is available for the current session, this
 * handler invokes external services (Kakao Local API and Open‑Meteo) to
 * enrich the retrieval accumulator with human‑readable summaries.
 *
 * <p>If no location has been recorded for the session, a notice is
 * appended instead of results.  Exceptions thrown by downstream clients
 * are logged and do not prevent further handlers from running.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationHandler implements RetrievalHandler {

    private final LocationMemory mem;
    private final KakaoLocalClient kakao;
    private final WeatherService weather;

    /** Default search radius (metres) when none is supplied via configuration. */
    @Value("${kakao.local.radius-m:1200}")
    private int defaultRadius;

    /** Default maximum number of results to return. */
    @Value("${kakao.local.max-results:8}")
    private int maxResults;

    private static final Pattern NEAR_EAT = Pattern.compile("(근처|주변).*(맛집|식당|밥집)|맛집", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEAR_CAFE = Pattern.compile("(근처|주변).*(카페)|가까운\\s*카페", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEATHER = Pattern.compile("(현재\\s*위치\\s*날씨|날씨)", Pattern.CASE_INSENSITIVE);
    private static final String FD6 = "FD6"; // Restaurant
    private static final String CE7 = "CE7"; // Cafe

    @Override
    public void handle(Query query, List<Content> acc) {
        String q = Optional.ofNullable(query).map(Query::text).orElse("");
        boolean askEat = NEAR_EAT.matcher(q).find();
        boolean askCafe = NEAR_CAFE.matcher(q).find();
        boolean askWx = WEATHER.matcher(q).find();
        // If no location intent detected, do nothing
        if (!(askEat || askCafe || askWx)) {
            return;
        }
        // Retrieve current session coordinates
        LocationMemory.GeoPoint loc = mem.current().orElse(null);
        if (loc == null) {
            acc.add(Content.from("위치 권한(좌표)이 없어 위치 기반 추천을 건너뜁니다."));
            return;
        }
        // Weather summary
        if (askWx) {
            try {
                weather.currentSummary(loc.lat(), loc.lon()).ifPresent(txt -> acc.add(Content.from("[현재 날씨]\n" + txt)));
            } catch (Exception e) {
                log.warn("[Location] weather call failed: {}", e.toString());
            }
        }
        // Places search
        String code = askCafe ? CE7 : (askEat ? FD6 : null);
        if (code != null) {
            try {
                List<KakaoLocalClient.Place> places = kakao.searchCategory(loc.lat(), loc.lon(), code, defaultRadius, maxResults);
                if (!places.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(askCafe ? "[근처 카페]\n" : "[근처 맛집]\n");
                    int i = 1;
                    for (KakaoLocalClient.Place p : places) {
                        String mapLink = String.format("https://map.kakao.com/link/to/%s,%f,%f", urlEncode(p.name()), p.lat(), p.lon());
                        sb.append(String.format("%d) %s — %s (☎ %s) · %sm · %s\n",
                                i++, p.name(), nvl(p.address(), "주소없음"), nvl(p.phone(), "-"), nvl(p.distanceMeters(), "?"), mapLink));
                    }
                    acc.add(Content.from(sb.toString()));
                }
            } catch (Exception e) {
                log.warn("[Location] place search failed: {}", e.toString());
            }
        }
    }

    private static String nvl(String s, String d) {
        return (s == null || s.isBlank()) ? d : s;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}