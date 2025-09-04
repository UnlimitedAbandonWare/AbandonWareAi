// src/main/java/com/example/lms/plugin/image/GeminiImageProperties.java
package com.example.lms.plugin.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini.image")
public class GeminiImageProperties {
    private boolean enabled = false;
    private String endpoint = "https://generativelanguage.googleapis.com";
    private String apiKey = "";
    private String model = "gemini-2.5-flash-image-preview";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}