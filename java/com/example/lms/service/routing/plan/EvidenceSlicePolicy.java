package com.example.lms.service.routing.plan;

import java.util.Map;

/**
 * Defines which (stable) attributes should invalidate a cached routing/planning decision.
 *
 * <p>Keep this policy pure and deterministic: it should not call external services or read
 * thread-local state directly. The caller must pass the attributes that affect the decision.
 */
public interface EvidenceSlicePolicy {

    /**
     * @param stage      logical decision stage (e.g. "queryPlan", "toolRoute")
     * @param input      primary input string (usually the user query)
     * @param attributes additional attributes that affect the decision (must be stable / deterministic)
     * @return a stable fingerprint string; if it changes, the cached decision must be invalidated
     */
    String fingerprint(String stage, String input, Map<String, Object> attributes);
}
