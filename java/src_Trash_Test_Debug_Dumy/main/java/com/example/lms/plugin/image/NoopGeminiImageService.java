package com.example.lms.plugin.image;

import java.util.List;

/**
 * A minimal no-operation implementation of {@link GeminiImagePort} that
 * always reports itself as unconfigured and returns empty lists for
 * image generation requests.  This bean is conditionally provided by
 * {@link com.example.lms.config.GeminiImageAutoConfiguration} whenever
 * no other {@code GeminiImagePort} bean is present in the Spring
 * context.  Its presence ensures that components depending on
 * {@code GeminiImagePort} can be wired even when the Gemini
 * integration has been disabled via configuration.
 */
public class NoopGeminiImageService implements GeminiImagePort {
    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public List<String> generate(String prompt, int count, String sizeHint) {
        return List.of();
    }

    @Override
    public List<String> edit(String prompt, String srcBase64, String mimeType) {
        return List.of();
    }
}