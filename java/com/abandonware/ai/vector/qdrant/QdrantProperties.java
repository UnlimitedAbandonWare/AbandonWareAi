package com.abandonware.ai.vector.qdrant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {
    private boolean enabled = true;
    private String url = "http://localhost:6333";
    private String apiKey = "";
    private String collection = "src111";
    private String distance = "cosine";
    private int vectorSize = 1536;
    private int timeoutMs = 3000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public String getDistance() { return distance; }
    public void setDistance(String distance) { this.distance = distance; }
    public int getVectorSize() { return vectorSize; }
    public void setVectorSize(int vectorSize) { this.vectorSize = vectorSize; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}