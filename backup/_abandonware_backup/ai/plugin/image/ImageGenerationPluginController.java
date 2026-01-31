package com.abandonware.ai.plugin.image;

import com.abandonware.ai.plugin.image.OpenAiImageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(prefix = "openai.image", name = "enabled", havingValue = "true")
@RequestMapping("/api/image-plugin")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.plugin.image.ImageGenerationPluginController
 * Role: controller
 * Key Endpoints: ANY /api/image-plugin/api/image-plugin
 * Dependencies: com.abandonware.ai.plugin.image.OpenAiImageService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.plugin.image.ImageGenerationPluginController
role: controller
api:
  - ANY /api/image-plugin/api/image-plugin
*/
public class ImageGenerationPluginController {
    private final OpenAiImageService imageService;
    public ImageGenerationPluginController(OpenAiImageService imageService) { this.imageService = imageService; }

    @PostMapping
    public String generate(@RequestBody String prompt) {
        return imageService.generate(prompt);
    }
}