package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;



/**
 * Configuration properties controlling location related features.  These
 * properties live under the {@code features.location} prefix.  They
 * enable or disable location based responses, toggle automatic
 * summarisation using an LLM and cap the number of nearby pharmacies
 * returned to the user.
 */
@ConfigurationProperties(prefix = "features.location")
@Validated
public class LocationFeatureProperties {

    /** Whether location detection and tool invocation are enabled. */
    private boolean enabled = true;
    /**
     * When true, the results returned by location tools (such as nearby
     * pharmacies or travel times) will be summarised via an LLM.  When
     * false the deterministic output from the tool is returned directly to
     * the user.
     */
    private boolean autosummarize = false;
    /**
     * Maximum number of pharmacies to return when searching for nearby
     * facilities.  This value is used by
     * {@link com.example.lms.location.LocationService#nearbyPharmacies}
     * to truncate the list.
     */
    private int maxPharmacies = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutosummarize() {
        return autosummarize;
    }

    public void setAutosummarize(boolean autosummarize) {
        this.autosummarize = autosummarize;
    }

    public int getMaxPharmacies() {
        return maxPharmacies;
    }

    public void setMaxPharmacies(int maxPharmacies) {
        this.maxPharmacies = maxPharmacies;
    }
}