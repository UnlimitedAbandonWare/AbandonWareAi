package com.example.lms.plugin.image;

import com.example.lms.plugin.image.storage.FileSystemImageStorage;
import com.example.lms.plugin.image.storage.ImageStorageProperties;
import com.example.lms.config.StaticResourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ImageStoragePrivacyTest {

    @TempDir
    Path tempDir;

    @Test
    void promptHintDoesNotReachPathOrPublicUrl() throws Exception {
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", null);
        FileSystemImageStorage storage = new FileSystemImageStorage(props, WebClient.builder().build());

        FileSystemImageStorage.Stored stored = storage.saveBase64Png("aW1hZ2UtYnl0ZXM=", "private prompt keyword");

        assertTrue(Files.exists(Path.of(stored.absolutePath())));
        assertFalse(stored.absolutePath().contains("private"));
        assertFalse(stored.absolutePath().contains("prompt"));
        assertFalse(stored.publicUrl().contains("private"));
        assertFalse(stored.publicUrl().contains("prompt"));
        assertTrue(Pattern.compile("[0-9a-fA-F-]{36}\\.png").matcher(Path.of(stored.absolutePath()).getFileName().toString()).matches());
        assertTrue(stored.publicUrl().startsWith("/generated-images/"));
    }

    @Test
    void defaultRootAndPrefixAreSharedWithStaticResourceConfig() throws Exception {
        ImageStorageProperties props = new ImageStorageProperties("", "", null);

        assertEquals("/generated-images/", FileSystemImageStorage.publicPrefix(props));
        assertTrue(FileSystemImageStorage.resolveRoot(props).endsWith(Path.of("Pictures", "AbandonWare", "img")));

        String staticResourceConfig = Files.readString(Path.of("main/java/com/example/lms/config/StaticResourceConfig.java"));
        assertTrue(staticResourceConfig.contains("FileSystemImageStorage.resolveRoot(props)"));
        assertTrue(staticResourceConfig.contains("FileSystemImageStorage.publicPrefix(props)"));
    }

    @Test
    void staticResourceConfigSkipsInvalidStorageRootInsteadOfBlockingBoot() {
        ImageStorageProperties props = new ImageStorageProperties("bad\u0000path", "/generated-images/", null);
        StaticResourceConfig config = new StaticResourceConfig(props);
        ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class);

        assertDoesNotThrow(() -> config.addResourceHandlers(registry));
        verifyNoInteractions(registry);
    }

    @Test
    void downloadRequiresHttpsAndHasDefaultSizeLimit() {
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", null);
        FileSystemImageStorage storage = new FileSystemImageStorage(props, WebClient.builder().build());

        IOException ex = assertThrows(IOException.class,
                () -> storage.downloadToStorage("http://example.test/image.png", "private prompt"));

        assertTrue(ex.getMessage().contains("https"));
        assertEquals(10L * 1024L * 1024L, FileSystemImageStorage.maxDownloadBytes(props));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/image.png",
            "https://localhost/image.png",
            "https://10.0.0.1/image.png",
            "https://169.254.169.254/latest/meta-data",
            "https://2130706433/image.png",
            "https://100.64.0.1/image.png",
            "https://198.18.0.1/image.png",
            "https://192.0.2.1/image.png",
            "https://[::1]/image.png",
            "https://[fc00::1]/image.png",
            "https://[2001:db8::1]/image.png"
    })
    void downloadRejectsLocalDestinationsBeforeExchange(String url) throws Exception {
        AtomicInteger exchangeCalls = new AtomicInteger();
        ExchangeFunction success = response("image/png", 4L, new byte[] {1, 2, 3, 4});
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    exchangeCalls.incrementAndGet();
                    return success.exchange(request);
                })
                .build();
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", null);
        FileSystemImageStorage storage = new FileSystemImageStorage(props, client);

        IOException ex = assertThrows(IOException.class,
                () -> storage.downloadToStorage(url, "private prompt"));

        assertTrue(ex.getMessage().contains("public host"));
        assertEquals(0, exchangeCalls.get());
        assertEquals(0L, regularFileCount(tempDir));
    }

    @Test
    void downloadAllowsPublicNumericDestination() throws Exception {
        AtomicInteger exchangeCalls = new AtomicInteger();
        ExchangeFunction success = response("image/png", 4L, new byte[] {1, 2, 3, 4});
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    exchangeCalls.incrementAndGet();
                    return success.exchange(request);
                })
                .build();
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", null);
        FileSystemImageStorage storage = new FileSystemImageStorage(props, client);

        FileSystemImageStorage.Stored stored = storage.downloadToStorage(
                "https://8.8.8.8/image.png",
                "private prompt");

        assertEquals(1, exchangeCalls.get());
        assertTrue(Files.isRegularFile(Path.of(stored.absolutePath())));
    }

    @Test
    void downloadRejectsContentLengthBeforeBodyIsPersisted() throws Exception {
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", 4L);
        WebClient client = WebClient.builder()
                .exchangeFunction(response("image/png", 8L, new byte[] {1, 2, 3, 4}))
                .build();
        FileSystemImageStorage storage = new FileSystemImageStorage(props, client);

        IOException ex = assertThrows(IOException.class,
                () -> storage.downloadToStorage("https://example.test/image.png", "private prompt"));

        assertTrue(ex.getMessage().contains("size limit"));
        assertEquals(0L, regularFileCount(tempDir));
    }

    @Test
    void downloadStreamingLimitDeletesPartialFile() throws Exception {
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", 4L);
        WebClient client = WebClient.builder()
                .exchangeFunction(response("image/png", -1L, new byte[] {1, 2, 3}, new byte[] {4, 5}))
                .build();
        FileSystemImageStorage storage = new FileSystemImageStorage(props, client);

        IOException ex = assertThrows(IOException.class,
                () -> storage.downloadToStorage("https://example.test/image.png", "private prompt"));

        assertTrue(ex.getMessage().contains("size limit"));
        assertEquals(0L, regularFileCount(tempDir));

        String source = Files.readString(Path.of("main/java/com/example/lms/plugin/image/storage/FileSystemImageStorage.java"));
        assertFalse(source.contains("bodyToMono(byte[].class)"));
        assertTrue(source.contains("DataBufferUtils.write"));
        assertTrue(source.contains("Image storage download preparation skipped stage=prepare_destination errorType="));
    }

    @Test
    void downloadAllowsOnlyImageContentTypes() throws Exception {
        ImageStorageProperties props = new ImageStorageProperties(tempDir.toString(), "/generated-images/", null);
        WebClient htmlClient = WebClient.builder()
                .exchangeFunction(response("text/html", -1L, "<html></html>".getBytes()))
                .build();
        FileSystemImageStorage htmlStorage = new FileSystemImageStorage(props, htmlClient);

        IOException ex = assertThrows(IOException.class,
                () -> htmlStorage.downloadToStorage("https://example.test/image", "private prompt"));
        assertTrue(ex.getMessage().contains("content type"));

        WebClient webpClient = WebClient.builder()
                .exchangeFunction(response("image/webp", -1L, new byte[] {1, 2, 3, 4}))
                .build();
        FileSystemImageStorage webpStorage = new FileSystemImageStorage(props, webpClient);
        FileSystemImageStorage.Stored stored = webpStorage.downloadToStorage("https://example.test/image.webp", "private prompt");

        assertTrue(stored.publicUrl().endsWith(".webp"));
        assertTrue(Files.exists(Path.of(stored.absolutePath())));
    }

    private static ExchangeFunction response(String contentType, long contentLength, byte[]... chunks) {
        return request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            ClientResponse.Builder builder = ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, contentType);
            if (contentLength >= 0) {
                builder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
            }
            return Mono.just(builder
                    .body(Flux.fromArray(chunks).map(factory::wrap))
                    .build());
        };
    }

    private static long regularFileCount(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
