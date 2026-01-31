package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;



/**
 * Configuration properties for Kakao API access.  This properties holder
 * binds to the {@code kakao} prefix in the application configuration and
 * exposes the REST API key required for calling the Kakao Local Search
 * and reverse geocoding endpoints.  When the key is blank the Kakao
 * clients will fail softly by returning empty results rather than
 * throwing exceptions.
 */
@ConfigurationProperties(prefix = "kakao")
@Validated
public class KakaoProperties {

    /**
     * Kakao REST API key used for both reverse geocoding and nearby
     * place searches.  When unset or blank the Kakao clients will skip
     * calling external services and return empty responses.
     */
    private String restKey;

    public String getRestKey() {
        return restKey;
    }

    public void setRestKey(String restKey) {
        this.restKey = restKey;
    }
}