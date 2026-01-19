package com.abandonwareai.nova.vector;

import java.util.Map;

public class EmbeddingDocument {
    private final String id;
    private final String text;
    private final Map<String, String> metadata;

    public EmbeddingDocument(String id, String text, Map<String, String> metadata) {
        this.id = id;
        this.text = text;
        this.metadata = metadata;
    }
    public String getId() { return id; }
    public String getText() { return text; }
    public Map<String, String> getMetadata() { return metadata; }
}