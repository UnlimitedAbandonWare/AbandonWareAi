
package com.abandonware.ai.agent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;




/**
 * Minimal Tavily web search client returning the standard RAG result schema.
 */
public class TavilyWebSearchRetriever {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper om = new ObjectMapper();

    public boolean isEnabled(String domain) {
        String key = System.getenv("TAVILY_API_KEY");
        if (key == null || key.isBlank()) return false;
        if (domain == null) return false;
        String d = domain.toLowerCase(Locale.ROOT);
        return d.equals("web") || d.equals("web+local") || d.equals("rrf");
    }

    public List<Map<String,Object>> search(String query, int topK, String domain) {
        if (!isEnabled(domain)) return List.of();
        String key = System.getenv("TAVILY_API_KEY");
        try {
            String lang = containsHangul(query) ? "ko" : "en";
            Map<String,Object> payload = new HashMap<>();
            payload.put("api_key", key);
            payload.put("query", query);
            payload.put("max_results", Math.max(3, Math.min(topK * 2, 10)));
            payload.put("search_depth", "advanced");
            payload.put("include_answer", false);
            payload.put("include_domains", Collections.emptyList());
            payload.put("exclude_domains", Collections.emptyList());
            payload.put("language", lang);

            String json = om.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                return List.of();
            }
            JsonNode root = om.readTree(resp.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return List.of();
            List<Map<String,Object>> out = new ArrayList<>();
            int r = 1;
            for (JsonNode item : results) {
                String title = text(item, "title");
                String content = text(item, "content");
                String url = text(item, "url");
                String id = url != null ? url : TextUtils.sha1((title == null ? "" : title) + "||" + (content == null ? "" : content));
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("title", title == null ? url : title);
                m.put("snippet", content == null ? "" : content);
                m.put("source", url);
                m.put("score", 1.0 / Math.max(1, r));
                m.put("rank", r);
                out.add(m);
                r++;
            }
            return out;
        } catch (IOException | InterruptedException e) {
            return List.of();
        }
    }

    private static boolean containsHangul(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_JAMO
                    || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}