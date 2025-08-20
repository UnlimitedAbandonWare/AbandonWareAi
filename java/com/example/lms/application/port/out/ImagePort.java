package com.example.lms.application.port.out;

import com.example.lms.dto.ImageTask;

/**
 * Port for image generation, editing or variation tasks.
 *
 * <p>The application core uses this interface to delegate image‑related
 * operations to an underlying client (e.g. OpenAI DALL‑E, Gemini Image
 * API).  The concrete implementation is responsible for calling the
 * external API and returning a URL pointing to the generated image.
 * Implementations should honour timeout and retry policies configured
 * via application properties and should surface any errors via
 * structured exceptions or SSE events.</p>
 */
public interface ImagePort {

    /**
     * Process an image task and return a URL to the generated or edited
     * image.  When the operation fails the implementation should throw
     * an exception describing the error.
     *
     * @param task description of the desired image operation
     * @return a publicly accessible URL of the resulting image
     * @throws Exception if the image operation fails
     */
    String handle(ImageTask task) throws Exception;
}