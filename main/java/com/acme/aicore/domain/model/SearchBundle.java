package com.acme.aicore.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * Container for search results returned by various providers.  Each bundle
 * carries a type identifier (e.g. "web" or "vector") which can be used
 * when assigning fusion weights.  Multiple bundles can be merged into a
 * single bundle by concatenating their document lists.
 */
public class SearchBundle {
    private final String type;
    private final List<Doc> docs;

    public SearchBundle(String type, List<Doc> docs) {
        this.type = type;
        this.docs = new ArrayList<>();
        if (docs != null) {
            for (Doc doc : docs) {
                if (doc != null) {
                    this.docs.add(doc);
                }
            }
        }
    }

    public String type() {
        return type;
    }

    public List<Doc> docs() {
        return Collections.unmodifiableList(docs);
    }

    public static SearchBundle merge(List<SearchBundle> bundles) {
        List<Doc> all = new ArrayList<>();
        String type = "mixed";
        if (bundles == null) {
            return new SearchBundle(type, all);
        }
        for (SearchBundle b : bundles) {
            if (b == null) {
                continue;
            }
            if (!"mixed".equals(type)) {
                type = b.type;
            }
            all.addAll(b.docs);
        }
        return new SearchBundle(type, all);
    }

    public static SearchBundle empty() {
        return new SearchBundle("empty", List.of());
    }

    /**
     * Minimal representation of a retrieved document used for ranking and
     * prompt construction.  Real implementations should include URL, title,
     * snippet and publication date.
     */
    public record Doc(String id, String title, String snippet, String url, String publishedAt) {
    }
}
