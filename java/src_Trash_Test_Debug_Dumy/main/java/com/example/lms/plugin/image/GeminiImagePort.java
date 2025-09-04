package com.example.lms.plugin.image;

import java.util.List;

/**
 * Port abstraction for image generation via the Gemini API.  This
 * interface defines the minimal operations required by the
 * {@link ImageGenerationPluginController} so
 * that the controller can remain agnostic of the concrete
 * implementation.  A no-op implementation is provided by
 * {@link NoopGeminiImageService} when the
 * integration is disabled or misconfigured.  Actual implementations
 * should handle network errors gracefully and return an empty list on
 * failure.
 */
public interface GeminiImagePort {
    /**
     * Indicates whether the Gemini image integration is properly
     * configured.  Controllers should consult this method before
     * attempting to generate or edit images.  Typical checks include
     * verifying that the endpoint and API key are non-empty.
     *
     * @return {@code true} when ready to serve requests, {@code false}
     * otherwise
     */
    boolean isConfigured();

    /**
     * Generates one or more images from a textual prompt.
     *
     * @param prompt   the description of the desired image
     * @param count    the number of images to generate (must be >=1)
     * @param sizeHint optional size hint (e.g. "1024x1024")
     * @return a list of public URLs (or an empty list on failure)
     */
    List<String> generate(String prompt, int count, String sizeHint);

    /**
     * Edits an existing image given as a Base64-encoded string.  The
     * provided prompt supplies additional instructions for the edit.
     *
     * @param prompt    optional instructions describing the desired edit
     * @param srcBase64 the source image encoded in Base64
     * @param mimeType  the MIME type of the source image (e.g. image/png)
     * @return a list containing a single public URL of the edited image, or
     * an empty list on failure
     */
    List<String> edit(String prompt, String srcBase64, String mimeType);
}