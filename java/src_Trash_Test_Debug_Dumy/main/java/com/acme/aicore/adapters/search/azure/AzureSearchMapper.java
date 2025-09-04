package com.acme.aicore.adapters.search.azure;

import com.acme.aicore.domain.model.SearchBundle;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts Azure Cognitive Search JSON responses into the internal
 * {@link SearchBundle.Doc} representation.  This mapper makes best effort
 * guesses about common field names; when fields are absent or empty they
 * fall back to sensible defaults.  The snippet is derived from available
 * content fields or, as a last resort, from the document's own JSON
 * representation.
 */
public final class AzureSearchMapper {

    private AzureSearchMapper() {}

    /**
     * Extract document list from an Azure search response.  If the
     * response is null or does not contain a "value" array, an empty list
     * is returned.
     *
     * @param response the parsed JSON response from Azure Cognitive Search
     * @return list of {@link SearchBundle.Doc} instances
     */
    public static List<SearchBundle.Doc> toDocuments(JsonNode response) {
        List<SearchBundle.Doc> docs = new ArrayList<>();
        if (response == null || !response.has("value") || !response.get("value").isArray()) {
            return docs;
        }
        for (JsonNode item : response.get("value")) {
            String id = getText(item, "id");
            // Azure typically includes a "@search.score" property; ignore here
            String title = getText(item, "title");
            // common snippet or content fields
            String snippet = getText(item, "snippet");
            if (snippet == null || snippet.isBlank()) {
                snippet = getText(item, "content");
            }
            if (snippet == null || snippet.isBlank()) {
                snippet = getText(item, "summary");
            }
            if (snippet == null && item.isTextual()) {
                snippet = item.asText();
            }
            // url field may be named differently (e.g. url, link)
            String url = getText(item, "url");
            if (url == null) {
                url = getText(item, "link");
            }
            docs.add(new SearchBundle.Doc(id, title, snippet, url, null));
        }
        return docs;
    }

    private static String getText(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String t = node.get(field).asText();
            return (t != null && !t.isBlank()) ? t : null;
        }
        return null;
    }
}