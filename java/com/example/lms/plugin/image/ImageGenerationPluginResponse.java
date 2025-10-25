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
}