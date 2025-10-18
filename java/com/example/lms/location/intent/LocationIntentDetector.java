package com.example.lms.location.intent;

import org.springframework.stereotype.Component;



/**
 * Simple heuristic based detector that scans a user query for Korean or
 * English keywords indicating a location based request.  The patterns
 * implemented here are intentionally conservative and may be replaced
 * with a more sophisticated natural language intent classifier in the
 * future.
 */
@Component
public class LocationIntentDetector {

    /**
     * Determine the intent from a free form user query.  The query is
     * trimmed and searched for keywords corresponding to each known
     * {@link LocationIntent}.  Matching is case insensitive.
     *
     * @param q the user query (may be null)
     * @return the detected intent, or {@link LocationIntent#NONE} when no
     *         location related keywords are present
     */
    public LocationIntent detect(String q) {
        if (q == null || q.isBlank()) return LocationIntent.NONE;
        String s = q.trim().toLowerCase(java.util.Locale.ROOT);
        // Where am I?  Expand Korean phrases with optional spacing and suffixes.
        // This pattern matches variations like "내 위치가 어디야", "내 위치가 어디임?", "지금 어디야?",
        // and the English phrase "where am i" (allowing arbitrary whitespace).  The optional
        // question mark at the end captures both declarative and interrogative forms.
        if (s.matches(".*(내\\s*위치\\s*가?\\s*어디(야|임)\\??|지금\\s*어디(야|임)\\??|where\\s*am\\s*i).*")) {
            return LocationIntent.WHERE_AM_I;
        }
        // Area brief
        if (s.matches(".*(근처|이 근처|동네|분위기|어떤 곳).*")) {
            return LocationIntent.AREA_BRIEF;
        }
        // Nearby POI: look for categories such as pharmacy, convenience store etc.
        if (s.matches(".*(약국|충전소|편의점|카페|주유소|은행|병원).*")) {
            return LocationIntent.NEARBY_POI;
        }
        // Travel time / directions
        if (s.matches(".*(얼마나 걸려|소요 시간|경로|길안내|가까운 길).*")) {
            return LocationIntent.TRAVEL_TIME;
        }
        return LocationIntent.NONE;
    }
}