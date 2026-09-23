package com.example.lms.service.rag;

import java.util.HashMap;
import java.util.Map;



/**
 * Utility for safely converting metadata maps into string-valued maps.  When
 * constructing {@link dev.langchain4j.data.document.Metadata} objects the
 * values must be strings; otherwise runtime exceptions may occur if a
 * {@link Map} with non-string values is passed directly.  Use this helper
 * to normalise the values of arbitrary maps into strings.
 */
public final class MetadataSafe {
    private MetadataSafe() {
    }

    /**
     * Convert the provided map into a new map with stringified values.  Null
     * values are omitted from the output.  When the input is null an empty
     * map is returned.
     *
     * @param in the input map (may be null)
     * @return a new map where all values are strings
     */
    public static Map<String, Object> asStrings(Map<String, ?> in) {
        Map<String, Object> out = new HashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, ?> e : in.entrySet()) {
            Object v = e.getValue();
            if (v != null) {
                out.put(e.getKey(), String.valueOf(v));
            }
        }
        return out;
    }
}