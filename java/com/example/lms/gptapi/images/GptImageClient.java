package com.example.lms.gptapi.images;

import com.example.lms.dto.ImageTask;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Client for GPT API image generation and editing.
 *
 * <p>This shim implementation does not perform any outbound network
 * requests.  Instead it generates a deterministic shim image
 * URL based on the provided prompt and operation mode.  In a real
 * implementation you would call the OpenAI or Gemini image API and
 * return the resulting download URL or base64 data.</p>
 */
@Component
public class GptImageClient {
    private static final Logger log = LoggerFactory.getLogger(GptImageClient.class);

    /**
     * Generate or edit an image based on the given task definition.  The
     * returned string is a shim URL containing the task mode and
     * a hash of the prompt.  This ensures that repeated calls with the
     * same parameters produce the same result.
     *
     * @param task the image generation or edit task
     * @return a URL string where the generated image can be downloaded
     */
    public String execute(ImageTask task) {
        if (task == null || task.getPrompt() == null || task.getPrompt().isBlank()) {
            return null;
        }
        // Compute a simple hash of the prompt to create a deterministic
        // shim path.  We avoid leaking the raw prompt by hashing it.
        int h = task.getPrompt().hashCode();
        String mode = (task.getMode() == null ? "generate" : task.getMode().toLowerCase());
        // Assemble a pseudo-URL.  In practice you would save the image to
        // persistent storage and return its absolute or relative URL.
        return String.format("/generated-images/%s-%08x.png", mode, h);
    }
}