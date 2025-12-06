package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;



/**
 * Configuration properties for the Gemini integration.  This class binds
 * the fields defined under the {@code gemini.*} namespace in the
 * application.properties file.  Use {@link org.springframework.beans.factory.annotation.Autowired}
 * to inject an instance of this class wherever Gemini credentials or
 * settings are required.
 */
@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {
    /**
     * API key used for authenticating with the Google GenAI/Gemini service.
     */
    private String apiKey;

    /**
     * Optional Google Cloud project identifier.  Relevant for Vertex
     * deployments.  May be empty when using the public developer backend.
     */
    private String projectId;

    /**
     * Location/region for Gemini resources (e.g. "global", "us-central1").
     */
    private String location;

    /**
     * Model name used for curation operations.  Defaults to a sensible
     * model such as "gemini-2.5-pro" when unspecified.
     */
    private String curatorModel;

    /**
     * Model name used for batch processing.  Defaults to a smaller model
     * such as "gemini-2.0-flash" when unspecified.
     */
    private String batchModel;

    /**
     * How long to wait for a connection to be established to the Gemini
     * endpoint, in milliseconds.
     */
    private int timeoutsConnectMs;

    /**
     * How long to wait for a response from the Gemini endpoint, in
     * milliseconds.
     */
    private int timeoutsReadMs;

    /**
     * Backend type.  Either "developer" for public access or "vertex" for
     * enterprise deployments.  Defaults to "developer".
     */
    private String backend;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCuratorModel() {
        return curatorModel;
    }

    public void setCuratorModel(String curatorModel) {
        this.curatorModel = curatorModel;
    }

    public String getBatchModel() {
        return batchModel;
    }

    public void setBatchModel(String batchModel) {
        this.batchModel = batchModel;
    }

    public int getTimeoutsConnectMs() {
        return timeoutsConnectMs;
    }

    public void setTimeoutsConnectMs(int timeoutsConnectMs) {
        this.timeoutsConnectMs = timeoutsConnectMs;
    }

    public int getTimeoutsReadMs() {
        return timeoutsReadMs;
    }

    public void setTimeoutsReadMs(int timeoutsReadMs) {
        this.timeoutsReadMs = timeoutsReadMs;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }
}