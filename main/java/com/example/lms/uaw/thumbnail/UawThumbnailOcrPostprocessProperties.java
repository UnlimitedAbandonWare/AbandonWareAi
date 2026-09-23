package com.example.lms.uaw.thumbnail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uaw.thumbnail.ocr-postprocess")
public class UawThumbnailOcrPostprocessProperties {

    private boolean enabled = false;
    private boolean indexEnabled = false;
    private int maxSpans = 6;
    private int maxTextChars = 1_200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIndexEnabled() {
        return indexEnabled;
    }

    public void setIndexEnabled(boolean indexEnabled) {
        this.indexEnabled = indexEnabled;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = maxSpans;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }
}
