package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.SearchBundle.Doc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;




/**
 * Utility for converting Brave search API responses into {@link SearchBundle}
 * instances.  The Brave API returns a JSON object with a top-level "web"
 * property containing an array of result objects under the "results" field.
 * Each result includes a title, description and url which are mapped onto
 * {@link SearchBundle.Doc} records.  Any parsing errors result in an empty
 * bundle.  This mapper is intentionally lightweight and avoids pulling in
 * additional dependencies beyond Jackson.
 */
final class BraveMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BraveMapper() {
        // Utility class
    }

    /**
     * Convert a raw JSON response into a {@link SearchBundle}.  When the
     * response cannot be parsed or does not contain the expected fields an
     * empty bundle is returned.
     *
     * @param json raw JSON returned by the Brave API
     * @return a {@code SearchBundle} containing zero or more documents
     */
    static SearchBundle toBundle(String json) {
        if (json == null || json.isBlank()) {
            return SearchBundle.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode results = root.path("web").path("results");
            if (!results.isArray()) {
                return SearchBundle.empty();
            }
            List<Doc> docs = new ArrayList<>();
            for (JsonNode n : results) {
                String title = n.path("title").asText("");
                String desc = n.path("description").asText("");
                String url = n.path("url").asText("");
                String id = url;
                String snippet = (title + " - " + desc).trim();
                docs.add(new Doc(id, title, snippet, url, null));
            }
            return new SearchBundle("web", docs);
        } catch (Exception e) {
            // Fall back to empty bundle on parsing failures
            return SearchBundle.empty();
        }
    }
}