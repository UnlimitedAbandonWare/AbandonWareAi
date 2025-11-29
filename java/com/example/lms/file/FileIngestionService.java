package com.example.lms.file;

import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


// InputStream은 더 이상 필요 없음
/**
 * Service responsible for extracting plain text from a variety of file formats.
 * When users upload attachments via the chat API the controller forwards the raw
 * bytes and MIME type to this class, which attempts to extract a concise
 * textual representation.  Supported formats include plain text (UTF-8 and
 * UTF-16 with optional BOM), JSON/XML, CSV, Markdown and PDF.  The output is
 * truncated to a configurable maximum character length to guard against
 * extremely large inputs.  Extraction failures are swallowed and logged and
 * result in a null return value to avoid breaking the chat flow.
 */
@Service
public class FileIngestionService {
    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    private static final int MAX_CHARS = 50_000;

    /**
     * Extract plain text from an uploaded file.  The strategy is chosen based on
     * the MIME type; unknown types fall back to an empty result.  Errors are
     * caught and logged; callers should handle a {@code null} return.
     *
     * @param fileName the name of the file
     * @param mimeType the declared MIME type (may be null or blank)
     * @param content  the raw file bytes
     * @return a string containing extracted text, or {@code null} on failure
     */
    public String extractText(String fileName, String mimeType, byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        String mt = (mimeType == null) ? "" : mimeType.toLowerCase(Locale.ROOT);
        try {
            if (mt.startsWith("text/") || mt.equals("application/json") || mt.equals("application/xml") || mt.equals("text/csv")) {
                // Detect UTF-8/UTF-16 BOM
                Charset charset = StandardCharsets.UTF_8;
                if (content.length >= 2) {
                    int b0 = content[0] & 0xFF;
                    int b1 = content[1] & 0xFF;
                    if (b0 == 0xFE && b1 == 0xFF) {
                        charset = StandardCharsets.UTF_16BE;
                    } else if (b0 == 0xFF && b1 == 0xFE) {
                        charset = StandardCharsets.UTF_16LE;
                    }
                }
                String text = new String(content, charset);
                return truncate(text);

            } else if (mt.equals("application/pdf")) {
                // PDFBox 3.x: Loader.loadPDF(byte[]) 사용
                try (PDDocument doc = Loader.loadPDF(content)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    String text = stripper.getText(doc);
                    return truncate(text);
                } catch (Throwable t) {
                    log.warn("[FileIngestion] PDF extraction failed for {}: {}", fileName, t.toString());
                    return null;
                }
            } else if (mt.endsWith("markdown") || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".md"))) {
                // treat markdown as UTF-8 text
                String text = new String(content, StandardCharsets.UTF_8);
                return truncate(text);
            } else {
                // unsupported MIME type
                log.debug("[FileIngestion] Unsupported MIME type {} for {}", mimeType, fileName);
                return null;
            }
        } catch (Exception e) {
            log.warn("[FileIngestion] extraction failed for {}: {}", fileName, e.toString());
            return null;
        }
    }

    private static String truncate(String text) {
        if (text == null) return null;
        if (text.length() > MAX_CHARS) {
            return text.substring(0, MAX_CHARS) + "\n[TRUNCATED]";
        }
        return text;
    }
}