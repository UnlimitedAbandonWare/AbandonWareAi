package com.example.lms.infrastructure.image;

import com.example.lms.application.port.out.ImagePort;
import com.example.lms.dto.ImageTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Minimal implementation of {@link ImagePort} that returns a dummy URL
 * for any image task.  This adapter acts as a stand‑in for a real
 * image generation service (e.g. OpenAI DALL‑E, Gemini API) so that the
 * application can compile and run without external dependencies.  It
 * logs the incoming task and returns a predictable placeholder URL.
 */
@Slf4j
@Component
public class GptImagePortAdapter implements ImagePort {

    @Override
    public String handle(ImageTask task) {
        // In a real implementation this method would call out to the
        // configured image generation service.  For now return a static
        // placeholder and log the invocation.
        log.debug("GptImagePortAdapter.handle invoked for task: {}", task);
        return "/images/placeholder.png";
    }
}