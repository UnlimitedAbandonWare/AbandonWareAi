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
        // 위치 기능 비활성화: 항상 NONE 반환
        return LocationIntent.NONE;
    }

}