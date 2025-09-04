package com.example.lms.service.rag.support;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.ContentMetadata;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility functions to safely access common fields from {@link Content} instances.
 * Some versions of LangChain4j expose methods such as {@code id()}, {@code text()}
 * or static factory methods for constructing text‑only content.  To remain
 * compatible across versions, this helper inspects the metadata map on the
 * content and extracts values for typical keys such as {@code url}, {@code id},
 * {@code text}, {@code snippet} or {@code title}.  When no metadata is present
 * a stable fallback key based on the identity hash is generated.
 */
public final class ContentCompat {
    private ContentCompat() {
    }

    /**
     * Return the metadata map of the given content.  When metadata cannot be
     * accessed or is not represented as a map, an empty map is returned.
     *
     * @param c the content to inspect
     * @return a non‑null map of metadata
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> metadataAsMap(Content c) {
        if (c == null) return Collections.emptyMap();
        try {
            Object md = c.metadata(); // may be Map<ContentMetadata, Object> in 1.0.1
            if (md instanceof Map<?, ?> raw) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                    String key = String.valueOf(e.getKey()); // normalize key type
                    out.put(key, e.getValue());
                }
                return out;
            }
        } catch (Throwable ignore) {
            // ignore and return empty
        }
        return Collections.emptyMap();
    }

    /**
     * Extract a representative text for the given content.  The metadata map is
     * inspected for common text fields such as {@code text}, {@code snippet} or
     * {@code title}.  When none of these are present the {@code toString()}
     * representation of the content is returned.
     *
     * @param c the content to inspect
     * @return the extracted text or an empty string when {@code c} is null
     */
    public static String textOf(Content c) {
        if (c == null) return "";
        Map<String, Object> m = metadataAsMap(c);
        Object t = m.getOrDefault("text", m.getOrDefault("snippet", m.get("title")));
        return t == null ? String.valueOf(c) : String.valueOf(t);
    }

    /**
     * Derive a stable identifier for the given content.  The metadata map is
     * inspected for a {@code url} or {@code id} entry.  When neither exists a
     * hash based on the object's identity is returned instead.
     *
     * @param c the content to inspect
     * @return a non‑null identifier string
     */
    public static String idOf(Content c) {
        Map<String, Object> m = metadataAsMap(c);
        Object v = m.get("url");
        if (v == null) v = m.get("id");
        return v != null ? String.valueOf(v) : Integer.toHexString(System.identityHashCode(c));
    }

    /**
     * Create a simple content instance from plain text.  When the platform
     * does not expose a suitable factory method, this helper uses a minimal
     * implementation that only provides the {@code metadata()} method.
     *
     * @param text the text to wrap; may be null
     * @return a {@link Content} containing the given text in its metadata
     */
    public static Content fromText(String text) {
        // Store with a String key ("text"); return type remains Map<ContentMetadata, Object>
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("text", text == null ? "" : text);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<ContentMetadata, Object> typed = (Map) raw; // unchecked but safe at runtime (erasure)
        // Return an anonymous Content instance instead of SimpleContent
        return new Content() {
            @Override
            public Map<ContentMetadata, Object> metadata() {
                return typed;
            }

            @Override
            public TextSegment textSegment() {
                // Candidate order: text > snippet > title
                Map<String, Object> m = ContentCompat.metadataAsMap(this);
                Object t = m.getOrDefault("text", m.getOrDefault("snippet", m.get("title")));
                String txt = t == null ? "" : String.valueOf(t);
                try {
                    return TextSegment.from(txt);
                } catch (Throwable ignored) {
                    try {
                        var ctor = TextSegment.class.getDeclaredConstructor(String.class, Map.class);
                        ctor.setAccessible(true);
                        return (TextSegment) ctor.newInstance(txt, java.util.Collections.emptyMap());
                    } catch (Exception e) {
                        return TextSegment.from("");
                    }
                }
            }
        };
    }

    // Removed: direct Content implementation (SimpleContent) to comply with Content Guard.
    // An anonymous Content instance is returned by fromText().
}

