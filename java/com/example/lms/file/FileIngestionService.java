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
        /**
     * Extract plain text from an uploaded file.
     *
     * <p>This implementation uses a combination of MIME type and file extension
     * to decide how to interpret the content. Text-like formats are decoded as
     * UTF-8/UTF-16, while PDF files are processed via PDFBox. Unsupported or
     * clearly binary formats return {@code null} so that callers can decide
     * whether to attempt a fallback.</p>
     *
     * @param fileName the name of the file (may be {@code null})
     * @param mimeType the declared MIME type (may be {@code null} or generic)
     * @param content  the raw file bytes
     * @return extracted plain text, or {@code null} on failure/unsupported type
     */
    public String extractText(String fileName, String mimeType, byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }

        String mt = (mimeType == null) ? "" : mimeType.toLowerCase(Locale.ROOT);
        String fn = (fileName == null) ? "" : fileName.toLowerCase(Locale.ROOT);

        try {
            // MERGE_HOOK:PROJ_AGENT::file_ingestion_v2
            // 1) 텍스트/코드 파일 판별 (MIME 또는 확장자 기준)
            boolean isText = mt.startsWith("text/")
                    || mt.contains("json") || mt.contains("xml") || mt.contains("csv") || mt.contains("yaml")
                    || mt.equals("application/javascript") || mt.equals("application/x-sh")
                    || fn.endsWith(".txt") || fn.endsWith(".json") || fn.endsWith(".xml") || fn.endsWith(".csv")
                    || fn.endsWith(".md") || fn.endsWith(".yml") || fn.endsWith(".yaml") || fn.endsWith(".properties")
                    || fn.endsWith(".java") || fn.endsWith(".py") || fn.endsWith(".js") || fn.endsWith(".ts")
                    || fn.endsWith(".html") || fn.endsWith(".css") || fn.endsWith(".sql") || fn.endsWith(".log");

            if (isText) {
                // UTF-8 / UTF-16 BOM 감지
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

            // 2) PDF: MIME 타입 또는 확장자 기준 (application/octet-stream + .pdf 대응)
            } else if (mt.equals("application/pdf") || fn.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(content)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    String text = stripper.getText(doc);
                    return truncate(text);
                } catch (Throwable t) {
                    log.warn("[FileIngestion] PDF extraction failed for {}: {}", fileName, t.toString());
                    return null;
                }

            // 3) 기타: 지원하지 않는 형식은 여기서 명확히 걸러낸다.
            } else {
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