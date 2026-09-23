package com.example.lms.plugin.image;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePluginNoInlineBase64ContractTest {

    @Test
    void imagePluginDoesNotReturnInlineBase64ByDefault() throws Exception {
        String service = Files.readString(Path.of("main/java/com/example/lms/plugin/image/OpenAiImageService.java"));
        String controller = Files.readString(Path.of("main/java/com/example/lms/plugin/image/ImageGenerationPluginController.java"));

        assertFalse(service.contains("data:image/png;base64"));
        assertTrue(service.contains("saveBase64Png"));
        assertTrue(controller.contains("openai.image.sync.enabled:false"));
        assertTrue(controller.contains("SYNC_GENERATE_DISABLED"));
    }
}
