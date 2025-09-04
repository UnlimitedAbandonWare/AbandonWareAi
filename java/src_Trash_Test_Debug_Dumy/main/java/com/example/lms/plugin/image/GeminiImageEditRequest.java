package com.example.lms.plugin.image;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request payload for editing an image using the Gemini image API.  The
 * {@code prompt} describes the desired transformation and must be
 * provided.  The {@code imageBase64} contains the source image
 * encoded as Base64 without any data URI prefix.  An optional
 * {@code mimeType} may be supplied to describe the source format,
 * defaulting to {@code image/png} when unspecified.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiImageEditRequest {
    @NotBlank
    private String prompt;
    @NotBlank
    private String imageBase64;
    private String mimeType = "image/png";

    public String getPrompt() {
        return prompt;
    }
    public void setPrompt(String s) {
        this.prompt = s;
    }
    public String getImageBase64() {
        return imageBase64;
    }
    public void setImageBase64(String s) {
        this.imageBase64 = s;
    }
    public String getMimeType() {
        return mimeType;
    }
    public void setMimeType(String s) {
        this.mimeType = s;
    }
}