package com.example.lms.gptapi.images;

import com.example.lms.dto.ImageTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * High level service for GPT API image operations.
 *
 * <p>This service orchestrates image generation and editing requests.  It
 * delegates to {@link GptImageClient} to perform the actual API call
 * and can include additional validation or processing logic as needed.
 * In this minimal implementation it simply forwards the task to the
 * client and returns the resulting URL.</p>
 */
@Service
@RequiredArgsConstructor
public class ImageGenerationService {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final GptImageClient client;

    /**
     * Perform an image operation based on the provided task.  Returns a
     * URL or path where the generated image can be fetched.  If the task
     * is null or incomplete, {@code null} will be returned.
     *
     * @param task the image task definition
     * @return a URL pointing to the resulting image, or {@code null}
     */
    public String handleImageTask(ImageTask task) {
        if (task == null) {
            return null;
        }
        try {
            return client.execute(task);
        } catch (Exception ex) {
            log.warn("Image generation failed: {}", ex.toString());
            return null;
        }
    }
}