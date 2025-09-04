package com.example.lms.service.docintel;

import com.acme.aicore.adapters.docintel.DocIntelClient;
import com.acme.aicore.adapters.docintel.DocIntelSecretRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Extracts plain text from uploaded documents using Azure Document Intelligence.
 *
 * <p>This service checks for the presence of secrets and enforces a size limit compatible
 * with the free (F0) tier. When extraction fails, it logs the exception and returns
 * {@code null} without interrupting the caller.</p>
 */
@Service
@Slf4j
@lombok.RequiredArgsConstructor
public class DocumentTextExtractor {

    /** Maximum allowed file size for the F0 tier (4MB). */
    private static final long F0_SIZE_LIMIT = 4L * 1024 * 1024;

    /** Client for invoking Azure Document Intelligence.  Injected by Spring. */
    private final DocIntelClient docIntelClient;

    /**
     * Attempts to extract text from the provided file. If secrets are unavailable, the
     * file is too large or empty, or an exception occurs, it returns {@code null}.
     *
     * @param file the multipart file to extract from
     * @return extracted text or {@code null} on failure
     */
    public String tryExtract(MultipartFile file) {
        try {
            if (!DocIntelSecretRegistry.available()) {
                log.debug("[DocIntel] secrets not available; skip");
                return null;
            }
            long size = file.getSize();
            if (size <= 0) {
                return null;
            }
            if (size > F0_SIZE_LIMIT) {
                log.warn("[DocIntel] file too large for F0 ({} bytes) – skip", size);
                return null;
            }
            byte[] bytes = file.getBytes();
            return docIntelClient.analyzeRead(bytes);
        } catch (Exception e) {
            log.warn("[DocIntel] extract failed: {}", e.toString());
            return null; // fail‑soft
        }
    }
}