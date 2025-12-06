package com.example.lms.plugin.image.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;




/**
 * A simple storage service that persists image bytes to the local file
 * system.  The storage root and public URL prefix are configured via
 * {@link ImageStorageProperties}.  Files are grouped by date and
 * assigned a random UUID prefix to minimise naming collisions.  Images
 * can be saved either from a Base64 encoded PNG or downloaded from a
 * remote URL using the provided OpenAI WebClient.  The returned
 * {@link Stored} record contains both the absolute file path and the
 * public URL path by which the file will be served.
 */
@Component
@RequiredArgsConstructor
public class FileSystemImageStorage {

    private final ImageStorageProperties props;

    /**
     * Dedicated WebClient used for downloading external image resources.
     * Qualify the injection with {@code openaiWebClient} to ensure
     * consistent timeout and buffer settings.
     */
    @Qualifier("openaiWebClient")
    private final WebClient openaiWebClient;

    /**
     * Data class representing a stored image.  Contains the absolute
     * filesystem path and the corresponding public URL path.
     */
    public record Stored(String absolutePath, String publicUrl) {}

    /**
     * Persist a Base64 encoded PNG string to the configured storage root.
     *
     * @param b64  the Base64 encoded PNG (without data URI prefix)
     * @param hint a hint used to generate the output filename
     * @return a {@link Stored} instance describing the file and its public URL
     * @throws IOException if writing the file fails
     */
    public Stored saveBase64Png(String b64, String hint) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(b64);
        return write(bytes, safeName(hint) + ".png");
    }

    /**
     * Download an image from the provided URL and persist it to the
     * configured storage root.  This method blocks until the image
     * download completes.
     *
     * @param url  the external URL from which to fetch the image
     * @param hint a hint used to generate the output filename
     * @return a {@link Stored} instance describing the file and its public URL
     * @throws IOException if writing the file fails
     */
    public Stored downloadToStorage(String url, String hint) throws IOException {
        byte[] bytes = openaiWebClient.get().uri(url)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        return write(bytes, safeName(hint) + ".png");
    }

    private Stored write(byte[] bytes, String filename) throws IOException {
        String root = props.root();
        // Default to user.home/Pictures/AbandonWare/img when not configured
        if (root == null || root.isBlank()) {
            root = Paths.get(System.getProperty("user.home"), "Pictures", "AbandonWare", "img").toString();
        }
        Path dir = Paths.get(root, LocalDate.now().toString());
        Files.createDirectories(dir);
        Path dst = dir.resolve(UUID.randomUUID() + "-" + filename);
        Files.write(dst, bytes, StandardOpenOption.CREATE_NEW);
        String publicPrefix = props.publicPrefix() == null ? "/generated-images/" : props.publicPrefix();
        // Build a relative URL by appending the date and filename to the public prefix
        String publicUrl = publicPrefix + dir.getFileName() + "/" + dst.getFileName();
        return new Stored(dst.toAbsolutePath().toString(), publicUrl);
    }

    private static String safeName(String s) {
        if (s == null) s = "img";
        // Replace any characters that could be problematic in filenames
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}