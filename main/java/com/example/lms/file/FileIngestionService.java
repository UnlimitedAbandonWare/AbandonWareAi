package com.example.lms.file;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    private static final int MAX_OFFICE_ENTRIES = 512;
    private static final int MAX_OFFICE_ENTRY_BYTES = 2_000_000;
    private static final int DEFAULT_MAX_ARCHIVE_ENTRIES = 300;
    private static final int DEFAULT_MAX_ARCHIVE_ENTRY_NAME_CHARS = 180;
    private static final int DEFAULT_MAX_ARCHIVE_SUMMARY_CHARS = 20_000;

    @Autowired(required = false)
    private Environment environment;

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
            boolean isOffice = isOfficeDocument(fn, mt);
            boolean isArchive = !isOffice && isArchiveDocument(fn, mt);
            // MERGE_HOOK:PROJ_AGENT::file_ingestion_v2
            // 1) 텍스트/코드 파일 판별 (MIME 또는 확장자 기준)
            boolean isText = !isOffice && (mt.startsWith("text/")
                    || mt.contains("json") || mt.contains("xml") || mt.contains("csv") || mt.contains("yaml")
                    || mt.equals("application/javascript") || mt.equals("application/x-sh")
                    || fn.endsWith(".txt") || fn.endsWith(".json") || fn.endsWith(".xml") || fn.endsWith(".csv")
                    || fn.endsWith(".md") || fn.endsWith(".yml") || fn.endsWith(".yaml") || fn.endsWith(".properties")
                    || fn.endsWith(".java") || fn.endsWith(".py") || fn.endsWith(".js") || fn.endsWith(".ts")
                    || fn.endsWith(".html") || fn.endsWith(".css") || fn.endsWith(".sql") || fn.endsWith(".log"));

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
                    log.warn("[FileIngestion] PDF extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length(),
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(t)), messageLength(t));
                    return null;
                }

            // 3) 기타: 지원하지 않는 형식은 여기서 명확히 걸러낸다.
            } else if (isOffice) {
                return extractOfficeText(fn, content);

            } else if (isArchive) {
                return extractArchiveTreeSummary(fn, content);

            } else {
                log.debug("[FileIngestion] Unsupported MIME type {} fileNameHash={} fileNameLength={}", mimeType, com.example.lms.trace.SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length());
                return null;
            }
        } catch (Exception e) {
            log.warn("[FileIngestion] extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length(),
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return null;
        }
    }

    private static boolean isOfficeDocument(String fn, String mt) {
        return fn.endsWith(".docx") || fn.endsWith(".pptx") || fn.endsWith(".xlsx") || fn.endsWith(".hwpx")
                || mt.contains("officedocument.wordprocessingml.document")
                || mt.contains("officedocument.presentationml.presentation")
                || mt.contains("officedocument.spreadsheetml.sheet")
                || mt.contains("application/x-hwpml")
                || mt.contains("application/haansoft-hwpx");
    }

    private static boolean isArchiveDocument(String fn, String mt) {
        return fn.endsWith(".zip")
                || mt.equals("application/zip")
                || mt.equals("application/x-zip-compressed")
                || mt.equals("multipart/x-zip")
                || mt.equals("application/octet-stream") && fn.endsWith(".zip");
    }

    private String extractArchiveTreeSummary(String fn, byte[] content) {
        int maxEntries = archiveInt("attachments.archive.maxEntries", DEFAULT_MAX_ARCHIVE_ENTRIES, 1, 10_000);
        int maxEntryNameChars = archiveInt("attachments.archive.maxEntryNameChars",
                DEFAULT_MAX_ARCHIVE_ENTRY_NAME_CHARS, 32, 2_000);
        int maxSummaryChars = archiveInt("attachments.archive.maxSummaryChars",
                DEFAULT_MAX_ARCHIVE_SUMMARY_CHARS, 512, 1_000_000);
        int entries = 0;
        int directories = 0;
        boolean truncated = false;
        Map<String, Integer> extCounts = new TreeMap<>();
        List<String> paths = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries++;
                String path = safeArchivePath(entry.getName(), maxEntryNameChars);
                if (entry.isDirectory()) {
                    directories++;
                } else {
                    extCounts.merge(extensionOf(path), 1, Integer::sum);
                }
                if (paths.size() < maxEntries) {
                    paths.add((entry.isDirectory() ? "[dir] " : "[file] ") + path);
                }
                if (entries >= maxEntries) {
                    truncated = zin.getNextEntry() != null;
                    break;
                }
            }
        } catch (Throwable t) {
            log.warn("[FileIngestion] Archive tree extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(fn), fn == null ? 0 : fn.length(),
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            return null;
        }
        if (entries == 0) {
            return null;
        }
        Map<String, Integer> boundedExtCounts = new LinkedHashMap<>();
        extCounts.entrySet().stream().limit(40).forEach(e -> boundedExtCounts.put(e.getKey(), e.getValue()));
        StringBuilder sb = new StringBuilder(1024);
        sb.append("### ARCHIVE TREE SUMMARY\n");
        sb.append("file=").append(fn == null || fn.isBlank() ? "archive.zip" : fn).append('\n');
        sb.append("sampledEntryCount=").append(entries).append('\n');
        sb.append("maxEntries=").append(maxEntries).append('\n');
        sb.append("truncated=").append(truncated).append('\n');
        sb.append("directoryCount=").append(directories).append('\n');
        sb.append("fileCount=").append(Math.max(0, entries - directories)).append('\n');
        sb.append("extensions=").append(boundedExtCounts).append('\n');
        sb.append("tree:\n");
        for (String path : paths) {
            sb.append("- ").append(path).append('\n');
        }
        if (truncated) {
            sb.append("- [truncated: maxEntries=").append(maxEntries).append("]\n");
        }
        return truncateArchiveSummary(sb.toString(), maxSummaryChars);
    }

    private static String safeArchivePath(String name, int maxEntryNameChars) {
        String value = name == null ? "" : name.replace('\\', '/').replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.contains("..")) {
            value = value.replace("..", "__");
        }
        if (value.length() > maxEntryNameChars) {
            value = value.substring(0, maxEntryNameChars) + "...";
        }
        return value.isBlank() ? "unnamed" : value;
    }

    private static String extensionOf(String path) {
        if (path == null || path.isBlank()) {
            return "[none]";
        }
        String name = path.toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "[none]";
        }
        return name.substring(dot + 1);
    }

    private static String truncateArchiveSummary(String text, int maxSummaryChars) {
        if (text == null || text.length() <= maxSummaryChars) {
            return text;
        }
        return text.substring(0, maxSummaryChars) + "\n[ARCHIVE SUMMARY TRUNCATED]";
    }

    private int archiveInt(String key, int def, int min, int max) {
        try {
            if (environment != null) {
                String raw = environment.getProperty(key);
                if (raw != null && !raw.isBlank()) {
                    int value = Integer.parseInt(raw.trim());
                    return Math.max(min, Math.min(max, value));
                }
            }
        } catch (Exception ignore) {
            traceSuppressed("fileIngestion.archiveInt", ignore);
            // Keep archive inspection fail-soft; defaults remain safe.
        }
        return def;
    }

    private static String extractOfficeText(String fn, byte[] content) {
        List<String> chunks = new ArrayList<>();
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null && entries < MAX_OFFICE_ENTRIES) {
                entries++;
                if (entry.isDirectory() || !isOfficeTextEntry(fn, entry.getName())) {
                    continue;
                }
                String text = xmlText(readBounded(zin, MAX_OFFICE_ENTRY_BYTES));
                if (text != null && !text.isBlank()) {
                    chunks.add(text);
                }
            }
        } catch (Throwable t) {
            log.warn("[FileIngestion] Office XML extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(fn), fn == null ? 0 : fn.length(),
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            return null;
        }
        return chunks.isEmpty() ? null : truncate(String.join("\n", chunks));
    }

    private static boolean isOfficeTextEntry(String fn, String name) {
        if (name == null) {
            return false;
        }
        String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!n.endsWith(".xml") || n.contains("..") || n.startsWith("/") || n.startsWith("meta-inf/")) {
            return false;
        }
        if (fn.endsWith(".docx")) {
            return n.equals("word/document.xml") || n.startsWith("word/header") || n.startsWith("word/footer");
        }
        if (fn.endsWith(".pptx")) {
            return n.startsWith("ppt/slides/") || n.startsWith("ppt/notesslides/");
        }
        if (fn.endsWith(".xlsx")) {
            return n.equals("xl/sharedstrings.xml") || n.startsWith("xl/worksheets/");
        }
        if (fn.endsWith(".hwpx")) {
            return n.startsWith("contents/") || n.startsWith("section") || n.contains("/section");
        }
        return false;
    }

    private static byte[] readBounded(ZipInputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("office XML entry too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String xmlText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = secureXmlFactory();
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(bytes)));
            List<String> text = new ArrayList<>();
            collectText(doc, text);
            String joined = String.join(" ", text).replaceAll("\\s+", " ").trim();
            return joined.isBlank() ? null : joined;
        } catch (Throwable ignore) {
            traceSuppressed("fileIngestion.xmlText", ignore);
            return null;
        }
    }

    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (javax.xml.parsers.ParserConfigurationException ignored) {
            traceSuppressed("fileIngestion.xmlFeature", ignored);
            // Parser implementations differ; FEATURE_SECURE_PROCESSING remains the baseline.
        }
    }

    private static void collectText(Node node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String value = node.getNodeValue();
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectText(children.item(i), out);
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("file.ingestion.suppressed.stage", safeStage);
        TraceStore.put("file.ingestion.suppressed.errorType", safeErrorType);
        TraceStore.put("file.ingestion.suppressed." + safeStage, true);
        TraceStore.put("file.ingestion.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable ignored) {
        if (ignored == null) {
            return "unknown";
        }
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }

    private static String truncate(String text) {
        if (text == null) return null;
        if (text.length() > MAX_CHARS) {
            int end = MAX_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            return text.substring(0, end) + "\n[TRUNCATED]";
        }
        return text;
    }
}
