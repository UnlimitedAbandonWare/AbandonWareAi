package com.example.lms.plugin;

import com.example.lms.plugin.image.GeminiImageProperties;
import com.example.lms.plugin.image.GeminiImageService;
import com.example.lms.plugin.storage.FileSystemImageStorage;
import com.example.lms.plugin.storage.ImageStorageProperties;
import org.junit.Test;

import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link GeminiImageService}.  These tests focus on
 * configuration and parameter validation rather than invoking the
 * external API.  Network calls are avoided by supplying empty inputs
 * which cause the service to return early.
 */
public class GeminiImageServiceTest {

    /**
     * When the API key is blank the service should report itself as
     * unconfigured.
     */
    @Test
    public void testIsConfiguredReturnsFalseForBlankKey() {
        GeminiImageProperties props = new GeminiImageProperties("https://generativelanguage.googleapis.com", "", null);
        ImageStorageProperties storageProps = new ImageStorageProperties(System.getProperty("java.io.tmpdir") + File.separator + "img", "/generated-images/");
        FileSystemImageStorage storage = new FileSystemImageStorage(storageProps, WebClient.create());
        GeminiImageService svc = new GeminiImageService(props, storage, WebClient.create(), null);
        assertFalse("Service should be unconfigured when API key is blank", svc.isConfigured());
    }

    /**
     * A blank prompt should result in no API call and an empty list.
     */
    @Test
    public void testGenerateReturnsEmptyForBlankPrompt() {
        GeminiImageProperties props = new GeminiImageProperties("https://generativelanguage.googleapis.com", "dummy-key", null);
        ImageStorageProperties storageProps = new ImageStorageProperties(System.getProperty("java.io.tmpdir") + File.separator + "img", "/generated-images/");
        FileSystemImageStorage storage = new FileSystemImageStorage(storageProps, WebClient.create());
        GeminiImageService svc = new GeminiImageService(props, storage, WebClient.create(), null);
        List<String> result = svc.generate("", 1, "Square image");
        assertNotNull(result);
        assertTrue("Expected empty list for blank prompt", result.isEmpty());
    }

    /**
     * A null or blank source image should result in an empty list and no API call.
     */
    @Test
    public void testEditReturnsEmptyForBlankSource() {
        GeminiImageProperties props = new GeminiImageProperties("https://generativelanguage.googleapis.com", "dummy-key", null);
        ImageStorageProperties storageProps = new ImageStorageProperties(System.getProperty("java.io.tmpdir") + File.separator + "img", "/generated-images/");
        FileSystemImageStorage storage = new FileSystemImageStorage(storageProps, WebClient.create());
        GeminiImageService svc = new GeminiImageService(props, storage, WebClient.create(), null);
        List<String> result = svc.edit("make bananas", "", "image/png");
        assertNotNull(result);
        assertTrue("Expected empty list for blank imageBase64", result.isEmpty());
    }
}