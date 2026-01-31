package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;



/**
 * Configuration properties for the Tmap Directions API.  The
 * {@code tmap} prefix binds the application configuration into this
 * bean so that the app key may be injected into the Tmap client.  When
 * the key is not provided the directions client will fall back to a
 * simple Haversine estimate.
 */
@ConfigurationProperties(prefix = "tmap")
@Validated
public class TmapProperties {

    /**
     * Tmap application key used to authenticate requests to the SK OpenAPI
     * directions endpoint.  When blank the directions client will avoid
     * network calls and instead compute an ETA using the Haversine
     * distance and an assumed average speed.
     */
    private String appKey;

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }
}