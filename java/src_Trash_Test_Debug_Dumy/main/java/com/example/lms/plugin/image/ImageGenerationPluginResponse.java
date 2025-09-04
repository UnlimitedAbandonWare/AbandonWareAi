package com.example.lms.plugin.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Response payload for the image generation plugin.  The API returns
 * a list of URLs pointing to the generated images along with an optional
 * reason for failure.  Consumers may choose to download these images
 * immediately or display the URLs as links.  The {@code reason} field is
 * populated when the request fails (e.g. due to missing API key) and
 * omitted on success.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageGenerationPluginResponse(List<String> imageUrls, String reason, Long etaSeconds, String expectedReadyAt) {

    /**
     * Factory method to construct a successful response.  Wraps the provided
     * list of URLs and omits the failure reason.
     *
     * @param urls the image URLs returned from the API
     * @return a response indicating success
     */
    public static ImageGenerationPluginResponse ok(List<String> urls) {
        return new ImageGenerationPluginResponse(urls, null, null, null);
    }

    /**
     * Factory method to construct an error response.  Populates an empty URL
     * list and sets the provided reason string.  The {@code reason} should
     * correspond to a machine-friendly error code (e.g. {@code NO_API_KEY}).
     *
     * @param reason the failure reason code
     * @return a response indicating an error
     */
    public static ImageGenerationPluginResponse error(String reason) {
        return new ImageGenerationPluginResponse(List.of(), reason, null, null);
    }

    /**
     * Factory method to construct a response with explicit reason.  This is
     * used by the Gemini endpoints to return a success/failure flag along
     * with the generated URLs.  When the list of URLs is empty the caller
     * may interpret the reason (e.g. {@code NO_IMAGE}) and handle
     * accordingly.
     *
     * @param urls   the generated image URLs (may be empty)
     * @param reason a human‑readable or code reason indicating the result
     * @return a response containing the URLs and reason
     */
    public static ImageGenerationPluginResponse success(java.util.List<String> urls, String reason) {
        return new ImageGenerationPluginResponse(urls, reason, null, null);
    }
}