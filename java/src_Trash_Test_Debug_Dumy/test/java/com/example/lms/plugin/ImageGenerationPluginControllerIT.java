package com.example.lms.plugin;

import com.example.lms.plugin.image.*;
import com.example.lms.plugin.storage.FileSystemImageStorage;
import com.example.lms.plugin.storage.ImageStorageProperties;
import org.junit.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration–style tests for the {@link ImageGenerationPluginController} focused
 * on the Gemini endpoints.  These tests construct the controller and its
 * dependencies manually without starting the Spring context.  They verify
 * response status and payload semantics when the Gemini API key is
 * missing and when editing with blank image data.
 */
public class ImageGenerationPluginControllerIT {

    /**
     * When no API key is configured the gemini endpoints should return a
     * 400 response with a NO_API_KEY error.
     */
    @Test
    public void testGeminiGenerateMissingApiKeyReturns400() {
        // Prepare services: gemini configured without API key
        GeminiImageProperties props = new GeminiImageProperties("https://generativelanguage.googleapis.com", "", null);
        ImageStorageProperties storageProps = new ImageStorageProperties(System.getProperty("java.io.tmpdir") + File.separator + "img", "/generated-images/");
        FileSystemImageStorage storage = new FileSystemImageStorage(storageProps, WebClient.create());
        GeminiImageService gemini = new GeminiImageService(props, storage, WebClient.create(), null);
        // OpenAI service is unused here; supply nulls
        OpenAiImageService openai = new OpenAiImageService(null, null, null);
        // Construct controller with null job services (not used in this test)
        ImageGenerationPluginController controller = new ImageGenerationPluginController(openai, null, null, gemini);
        ImageGenerationPluginRequest req = new ImageGenerationPluginRequest();
        req.setPrompt("banana bowl");
        req.setCount(1);
        req.setSize("1024x1024");
        ResponseEntity<ImageGenerationPluginResponse> resp = controller.geminiGenerate(req);
        assertEquals("Expected HTTP 400 for missing API key", 400, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        assertEquals("NO_API_KEY", resp.getBody().reason());
    }

    /**
     * When editing with a blank source image and a configured API key the
     * controller should return an empty urls list with reason NO_IMAGE.
     */
    @Test
    public void testGeminiEditBlankImageReturnsNoImage() {
        // Gemini configured with a dummy API key; this allows the request to proceed
        GeminiImageProperties props = new GeminiImageProperties("https://generativelanguage.googleapis.com", "dummy-key", null);
        ImageStorageProperties storageProps = new ImageStorageProperties(System.getProperty("java.io.tmpdir") + File.separator + "img", "/generated-images/");
        FileSystemImageStorage storage = new FileSystemImageStorage(storageProps, WebClient.create());
        GeminiImageService gemini = new GeminiImageService(props, storage, WebClient.create(), null);
        OpenAiImageService openai = new OpenAiImageService(null, null, null);
        ImageGenerationPluginController controller = new ImageGenerationPluginController(openai, null, null, gemini);
        GeminiImageEditRequest req = new GeminiImageEditRequest();
        req.setPrompt("replace fruit");
        req.setImageBase64("");
        req.setMimeType("image/png");
        ResponseEntity<ImageGenerationPluginResponse> resp = controller.geminiEdit(req);
        assertEquals("Expected HTTP 200 for blank image with API key", 200, resp.getStatusCodeValue());
        assertNotNull(resp.getBody());
        List<String> urls = resp.getBody().imageUrls();
        assertNotNull(urls);
        assertTrue("Urls should be empty when no image provided", urls.isEmpty());
        assertEquals("NO_IMAGE", resp.getBody().reason());
    }
}