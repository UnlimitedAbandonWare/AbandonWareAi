package com.example.lms.location;

import com.example.lms.location.places.PlacesClient;
import com.example.lms.location.route.DirectionsClient;
import java.util.List;



/**
 * Helper class with static formatting routines for rendering location
 * related responses into human readable strings.  These methods are
 * deliberately simple and return Korean messages directly rather than
 * delegating to the LLM.  See {@link com.example.lms.location.LocationService}
 * and {@link com.example.lms.location.LocationController} for usage.
 */
public final class Formatters {
    private Formatters() {}

    /**
     * Render a list of nearby points of interest into a bullet list.  When
     * the list is empty a fallback message is returned.
     *
     * @param list the list of {@link PlacesClient.Place} objects
     * @return a formatted list or fallback message
     */
    public static String renderNearbyList(List<PlacesClient.Place> list) {
        if (list == null || list.isEmpty()) {
            return "근처에 결과가 없습니다.";
        }
        StringBuilder sb = new StringBuilder();
        for (PlacesClient.Place p : list) {
            if (p == null) continue;
            sb.append("- ")
              .append(p.name() == null ? "(이름 없음)" : p.name());
            if (p.distanceM() > 0) {
                long m = Math.round(p.distanceM());
                sb.append(" (약 ")
                  .append(m)
                  .append("m)");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Render an estimated travel time into a human friendly sentence.
     * If no estimate is available a fallback message is returned.
     *
     * @param eta the estimated travel time result (may be null)
     * @return a formatted sentence
     */
    public static String renderEta(DirectionsClient.EtaResult eta) {
        if (eta == null) {
            return "경로를 계산할 수 없습니다.";
        }
        long minutes = Math.round(eta.seconds() / 60.0);
        String desc = eta.description();
        if (desc != null && !desc.isBlank()) {
            return desc;
        }
        return String.format("예상 소요 시간은 약 %d분입니다.", minutes);
    }
}