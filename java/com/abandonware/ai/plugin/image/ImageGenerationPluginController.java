package com.abandonware.ai.plugin.image;

import com.abandonware.ai.plugin.image.OpenAiImageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(prefix = "openai.image", name = "enabled", havingValue = "true")
@RequestMapping("/api/image-plugin")
public class ImageGenerationPluginController {
    private final OpenAiImageService imageService;
    public ImageGenerationPluginController(OpenAiImageService imageService) { this.imageService = imageService; }

    @PostMapping
    public String generate(@RequestBody String prompt) {
        return imageService.generate(prompt);
    }
}