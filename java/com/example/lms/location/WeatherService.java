package com.example.lms.location;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;

/**
 * Service for retrieving simple weather summaries from the Open‑Meteo API.
 *
 * <p>The API requires no authentication and supports lat/lon queries.  Only
 * the current temperature, apparent temperature, precipitation and weather
 * code are extracted.  If any exception occurs during the HTTP call,
 * an empty optional is returned.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    @Value("${weather.base-url:https://api.open-meteo.com}")
    private String baseUrl;

    /**
     * Fetch a brief summary of the current weather conditions for the given
     * coordinate.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return an optional human readable summary (e.g. "현재 기온 20.3°C…")
     */
    public Optional<String> currentSummary(double lat, double lon) {
        try {
            String uri = String.format("%s/v1/forecast?latitude=%f&longitude=%f&current=temperature_2m,apparent_temperature,precipitation,weather_code&timezone=auto",
                    baseUrl, lat, lon);
            Map<?, ?> body = WebClient.create().get().uri(uri).retrieve().bodyToMono(Map.class).block();
            Map<String, Object> cur = (Map<String, Object>) body.get("current");
            if (cur == null) {
                return Optional.empty();
            }
            Double t = toDouble(cur.get("temperature_2m"));
            Double feels = toDouble(cur.get("apparent_temperature"));
            Double p = toDouble(cur.get("precipitation"));
            Integer code = toInt(cur.get("weather_code"));
            if (t == null || feels == null || p == null || code == null) {
                return Optional.empty();
            }
            String txt = String.format("현재 기온 %.1f°C (체감 %.1f°C), 강수량 %.1fmm, 상태코드 %d", t, feels, p, code);
            return Optional.of(txt);
        } catch (Exception e) {
            log.warn("[Weather] failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}