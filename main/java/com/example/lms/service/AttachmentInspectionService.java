package com.example.lms.service;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ocr.OcrService;
import com.example.lms.service.ocr.OcrSpan;
import com.example.lms.service.ocr.Rect;
import com.example.lms.trace.SafeRedactor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AttachmentInspectionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttachmentInspectionService.class);
    private static final int MAX_PREVIEW_CHARS = 8_000;
    private static final int MAX_SPANS = 250;

    private final FileIngestionService fileIngestionService;
    private final AttachmentService attachmentService;
    private final ObjectProvider<OcrService> ocrServiceProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final Environment environment;

    public AttachmentInspectionService(
            FileIngestionService fileIngestionService,
            AttachmentService attachmentService,
            ObjectProvider<OcrService> ocrServiceProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            Environment environment) {
        this.fileIngestionService = fileIngestionService;
        this.attachmentService = attachmentService;
        this.ocrServiceProvider = ocrServiceProvider;
        this.debugEventStoreProvider = debugEventStoreProvider;
        this.environment = environment;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        try {
            TraceStore.put("attachment.suppressed." + safeStage, true);
            TraceStore.put("attachment.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[AttachmentInspection] suppressed trace failed stage={} errorType={}",
                    safeStage, traceFailure.getClass().getSimpleName());
        }
    }

    public Map<String, Object> readiness() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean enabled = getBoolean("ocr.enabled", false);
        boolean ragEnabled = getBoolean("rag.ocr.enabled", false);
        String language = getString("ocr.language", "eng+kor");
        float minConfidence = getFloat("ocr.min-confidence", getFloat("ocr.minConfidence", 0.65f));
        long timeoutMs = getLong("ocr.timeout-ms", getLong("ocr.timeoutMs", 900L));
        String datapath = getString("ocr.datapath", "");
        String tessdataPrefix = System.getenv("TESSDATA_PREFIX");
        NativeState nativeState = detectNative();
        TessdataState tessdataState = detectTessdata(datapath, tessdataPrefix, language);
        boolean ocrBeanPresent = ocrServiceProvider.getIfAvailable() != null;

        String failureClass = "ready";
        if (!enabled) {
            failureClass = "disabled";
        } else if (!nativeState.available) {
            failureClass = "native_missing";
        } else if (!tessdataState.available) {
            failureClass = "tessdata_missing";
        } else if (!ocrBeanPresent) {
            failureClass = "missing_ocr_bean";
        }
        boolean ready = enabled && nativeState.available && tessdataState.available && ocrBeanPresent;

        out.put("enabled", enabled);
        out.put("ragEnabled", ragEnabled);
        out.put("ready", ready);
        out.put("nativeAvailable", nativeState.available);
        out.put("nativeSource", nativeState.source);
        out.put("tessdataAvailable", tessdataState.available);
        out.put("tessdataSource", tessdataState.source);
        out.put("datapathConfigured", datapath != null && !datapath.isBlank());
        out.put("tessdataPrefixPresent", tessdataPrefix != null && !tessdataPrefix.isBlank());
        out.put("missingLanguages", tessdataState.missingLanguages);
        out.put("ocrBeanPresent", ocrBeanPresent);
        out.put("failureClass", failureClass);
        out.put("language", language);
        out.put("minConfidence", minConfidence);
        out.put("timeoutMs", timeoutMs);
        out.put("checkedAt", Instant.now().toString());
        TraceStore.put("ocr.readiness.ready", ready);
        TraceStore.put("ocr.readiness.failureClass", failureClass);
        return out;
    }

    public Map<String, Object> inspect(List<MultipartFile> files, String sessionId, List<AttachmentDto> saved) {
        List<MultipartFile> safeFiles = files == null ? List.of() : files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        List<AttachmentDto> safeSaved = saved == null ? List.of() : saved;
        Map<String, Object> readiness = readiness();
        List<Map<String, Object>> inspected = new ArrayList<>();
        long started = System.currentTimeMillis();
        for (int i = 0; i < safeFiles.size(); i++) {
            AttachmentDto dto = i < safeSaved.size() ? safeSaved.get(i) : null;
            inspected.add(inspectOne(safeFiles.get(i), dto, readiness));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionIdPresent", sessionId != null && !sessionId.isBlank());
        out.put("fileCount", safeFiles.size());
        out.put("savedCount", safeSaved.size());
        out.put("readiness", readiness);
        out.put("files", inspected);
        out.put("tookMs", Math.max(0, System.currentTimeMillis() - started));
        emitInspectEvent(out, inspected, readiness);
        return out;
    }

    private Map<String, Object> inspectOne(MultipartFile file, AttachmentDto dto, Map<String, Object> readiness) {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = safeName(file.getOriginalFilename());
        String contentType = file.getContentType();
        byte[] bytes = readBytes(file);
        String type = classify(name, contentType);
        String text = null;
        String failureClass = null;
        List<Map<String, Object>> spans = List.of();
        Map<String, Object> image = imageDimensions(bytes, type);

        out.put("id", dto == null ? null : dto.id());
        out.put("name", name);
        out.put("size", file.getSize());
        out.put("contentType", contentType);
        out.put("type", type);
        out.put("sha256", SafeRedactor.hash12(name + ":" + file.getSize() + ":" + contentType));
        if (!image.isEmpty()) {
            out.putAll(image);
        }

        if ("text".equals(type) || "pdf".equals(type) || "office".equals(type) || "archive".equals(type)) {
            text = extractText(name, contentType, bytes);
        }

        boolean hasText = text != null && !text.isBlank();
        boolean ocrReady = Boolean.TRUE.equals(readiness.get("ready"));
        boolean ocrAttempted = false;
        if (!hasText && "pdf".equals(type)) {
            if (ocrReady) {
                ocrAttempted = true;
                OcrResult ocr = ocr(bytes, type);
                text = ocr.text;
                spans = ocr.spans;
                failureClass = ocr.failureClass;
            } else {
                failureClass = "text_layer_empty_ocr_required";
            }
        } else if (!hasText && "image".equals(type)) {
            if (ocrReady) {
                ocrAttempted = true;
                OcrResult ocr = ocr(bytes, type);
                text = ocr.text;
                spans = ocr.spans;
                failureClass = ocr.failureClass;
            } else {
                failureClass = String.valueOf(readiness.getOrDefault("failureClass", "disabled"));
            }
        } else if (!hasText && "office".equals(type)) {
            failureClass = "office_empty";
        } else if (!hasText && "archive".equals(type)) {
            failureClass = looksLikeZip(bytes) ? "archive_empty" : "archive_error";
        } else if (!hasText && "unsupported".equals(type)) {
            failureClass = "unsupported";
        }

        if (text != null && !text.isBlank() && dto != null) {
            attachmentService.cacheExtractedText(dto.id(), text);
        }
        String preview = preview(text);
        out.put("source", sourceFor(type, ocrAttempted, hasText));
        out.put("textPreview", preview);
        out.put("textLength", text == null ? 0 : text.length());
        out.put("truncated", text != null && text.length() > MAX_PREVIEW_CHARS);
        out.put("failureClass", failureClass);
        out.put("spans", spans);
        out.put("ocr", ocrSummary(ocrAttempted, ocrReady, spans, failureClass));
        recordAttachmentTrace(type, preview, text, failureClass);
        return out;
    }

    private String extractText(String name, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return fileIngestionService.extractText(name, contentType, bytes);
        } catch (Throwable ignore) {
            traceSuppressed("attachment.extractText", ignore);
            return null;
        }
    }

    private OcrResult ocr(byte[] bytes, String type) {
        OcrService ocrService = ocrServiceProvider.getIfAvailable();
        if (ocrService == null || bytes == null || bytes.length == 0) {
            return OcrResult.failure("missing_ocr_bean");
        }
        List<OcrSpan> all = new ArrayList<>();
        try {
            if ("pdf".equals(type)) {
                try (PDDocument doc = Loader.loadPDF(bytes)) {
                    int maxPages = Math.max(1, Math.min(doc.getNumberOfPages(), getInt("ocr.inspect.maxPdfPages", 2)));
                    int dpi = Math.max(72, Math.min(220, getInt("ocr.inspect.dpi", 160)));
                    PDFRenderer renderer = new PDFRenderer(doc);
                    for (int page = 0; page < maxPages; page++) {
                        BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                        byte[] imageBytes = encodePng(image);
                        all.addAll(withPage(ocrService.extract(new ByteArrayInputStream(imageBytes), ocrOptions()), page + 1));
                    }
                }
            } else {
                all.addAll(withPage(ocrService.extract(new ByteArrayInputStream(bytes), ocrOptions()), 1));
            }
        } catch (Throwable ignore) {
            traceSuppressed("attachment.ocr", ignore);
            return OcrResult.failure("ocr_error");
        }
        List<OcrSpan> limited = all.stream()
                .filter(s -> s != null && s.text() != null && !s.text().isBlank())
                .limit(MAX_SPANS)
                .toList();
        String text = limited.stream().map(OcrSpan::text).collect(Collectors.joining(" "));
        List<Map<String, Object>> spans = limited.stream().map(this::spanMap).toList();
        String failureClass = limited.isEmpty() ? "zero_spans" : null;
        return new OcrResult(text, spans, failureClass);
    }

    private OcrService.OcrOptions ocrOptions() {
        return new OcrService.OcrOptions(
                getString("ocr.language", "eng+kor"),
                Math.max(1, getInt("ocr.inspect.maxPdfPages", 2)),
                getFloat("ocr.min-confidence", getFloat("ocr.minConfidence", 0.65f)));
    }

    private static List<OcrSpan> withPage(List<OcrSpan> spans, int page) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        List<OcrSpan> out = new ArrayList<>(spans.size());
        for (OcrSpan span : spans) {
            if (span == null) {
                continue;
            }
            int p = span.page() > 0 ? span.page() : page;
            out.add(new OcrSpan(span.text(), span.bbox(), span.confidence(), p));
        }
        return out;
    }

    private Map<String, Object> spanMap(OcrSpan span) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", preview(span.text()));
        out.put("confidence", span.confidence());
        out.put("page", span.page());
        Rect box = span.bbox();
        if (box != null) {
            Map<String, Object> bbox = new LinkedHashMap<>();
            bbox.put("x", box.x());
            bbox.put("y", box.y());
            bbox.put("w", box.w());
            bbox.put("h", box.h());
            out.put("bbox", bbox);
        }
        return out;
    }

    private Map<String, Object> ocrSummary(boolean attempted, boolean ready, List<Map<String, Object>> spans, String failureClass) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attempted", attempted);
        out.put("ready", ready);
        out.put("spanCount", spans == null ? 0 : spans.size());
        out.put("avgConfidence", averageConfidence(spans));
        out.put("failureClass", failureClass);
        return out;
    }

    private static double averageConfidence(List<Map<String, Object>> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0.0d;
        }
        double sum = 0.0d;
        int count = 0;
        for (Map<String, Object> span : spans) {
            Object confidence = span.get("confidence");
            if (confidence instanceof Number n) {
                double value = n.doubleValue();
                if (!Double.isFinite(value)) {
                    continue;
                }
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0.0d : Math.round((sum / count) * 1000.0d) / 1000.0d;
    }

    private void emitInspectEvent(Map<String, Object> response, List<Map<String, Object>> inspected, Map<String, Object> readiness) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileCount", response.get("fileCount"));
        data.put("savedCount", response.get("savedCount"));
        data.put("tookMs", response.get("tookMs"));
        data.put("readinessFailureClass", readiness.get("failureClass"));
        data.put("ocrReady", readiness.get("ready"));
        data.put("files", inspected.stream().map(this::safeFileEvent).toList());
        TraceStore.put("attachment.inspect.fileCount", response.get("fileCount"));
        TraceStore.put("attachment.inspect.savedCount", response.get("savedCount"));
        DebugEventStore store = debugEventStoreProvider.getIfAvailable();
        if (store != null) {
            store.emit(DebugProbeType.ORCHESTRATION, DebugEventLevel.INFO,
                    "attachment.inspect", "Attachment inspection completed",
                    "AttachmentInspectionService.inspect", data, null);
        }
    }

    private Map<String, Object> safeFileEvent(Map<String, Object> file) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nameHash", SafeRedactor.hash12(String.valueOf(file.get("name"))));
        out.put("size", file.get("size"));
        out.put("contentType", file.get("contentType"));
        out.put("type", file.get("type"));
        out.put("textLength", file.get("textLength"));
        out.put("failureClass", file.get("failureClass"));
        out.put("source", file.get("source"));
        Object ocr = file.get("ocr");
        if (ocr instanceof Map<?, ?> m) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("attempted", m.get("attempted"));
            summary.put("ready", m.get("ready"));
            summary.put("spanCount", m.get("spanCount"));
            summary.put("failureClass", m.get("failureClass"));
            out.put("ocr", summary);
        }
        return out;
    }

    private Map<String, Object> imageDimensions(byte[] bytes, String type) {
        if (!"image".equals(type) || bytes == null || bytes.length == 0) {
            return Map.of();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("imageWidth", image.getWidth());
            out.put("imageHeight", image.getHeight());
            return out;
        } catch (Throwable ignore) {
            traceSuppressed("attachment.imageMeta", ignore);
            return Map.of();
        }
    }

    private static byte[] encodePng(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file == null ? new byte[0] : file.getBytes();
        } catch (Throwable ignore) {
            traceSuppressed("attachment.readBytes", ignore);
            return new byte[0];
        }
    }

    private static String classify(String name, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String fn = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (isArchive(name, contentType)) {
            return "archive";
        }
        if (isOffice(name, contentType)) {
            return "office";
        }
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("csv")
                || ct.contains("yaml") || fn.endsWith(".txt") || fn.endsWith(".md") || fn.endsWith(".json")
                || fn.endsWith(".csv") || fn.endsWith(".xml") || fn.endsWith(".yml") || fn.endsWith(".yaml")
                || fn.endsWith(".properties") || fn.endsWith(".log")) {
            return "text";
        }
        if (ct.equals("application/pdf") || fn.endsWith(".pdf")) {
            return "pdf";
        }
        if (ct.startsWith("image/") || fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg")
                || fn.endsWith(".gif") || fn.endsWith(".bmp") || fn.endsWith(".tif") || fn.endsWith(".tiff")) {
            return "image";
        }
        return "unsupported";
    }

    private static boolean isOffice(String name, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String fn = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return fn.endsWith(".docx") || fn.endsWith(".pptx") || fn.endsWith(".xlsx") || fn.endsWith(".hwpx")
                || ct.contains("officedocument.wordprocessingml.document")
                || ct.contains("officedocument.presentationml.presentation")
                || ct.contains("officedocument.spreadsheetml.sheet")
                || ct.contains("application/x-hwpml")
                || ct.contains("application/haansoft-hwpx");
    }

    private static boolean isArchive(String name, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String fn = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return fn.endsWith(".zip")
                || ct.equals("application/zip")
                || ct.equals("application/x-zip-compressed")
                || ct.equals("multipart/x-zip")
                || (ct.equals("application/octet-stream") && fn.endsWith(".zip"));
    }

    private static String sourceFor(String type, boolean ocrAttempted, boolean hasPlainText) {
        if (ocrAttempted) {
            return "ocr";
        }
        if ("pdf".equals(type) && hasPlainText) {
            return "pdfbox";
        }
        if ("text".equals(type) && hasPlainText) {
            return "text";
        }
        if ("office".equals(type) && hasPlainText) {
            return "office-xml";
        }
        if ("archive".equals(type) && hasPlainText) {
            return "archive-tree";
        }
        return "none";
    }

    private static void recordAttachmentTrace(String type, String preview, String text, String failureClass) {
        try {
            if (failureClass != null && !failureClass.isBlank()) {
                TraceStore.put("attachment.text.emptyReason", SafeRedactor.traceLabelOrFallback(failureClass, "unknown"));
            }
            if ("archive".equals(type)) {
                TraceStore.put("attachment.archive.entryCount", archiveEntryCount(preview));
                TraceStore.put("attachment.archive.extCounts", archiveExtCounts(preview));
            }
            if (text == null || text.isBlank()) {
                TraceStore.put("attachment.text.extracted", false);
            }
        } catch (Throwable ignore) {
            traceSuppressed("attachment.recordTrace", ignore);
        }
    }

    private static int archiveEntryCount(String preview) {
        if (preview == null) {
            return 0;
        }
        String marker = "sampledEntryCount=";
        int start = preview.indexOf(marker);
        if (start < 0) {
            marker = "entryCount=";
            start = preview.indexOf(marker);
            if (start < 0) {
                return 0;
            }
        }
        int valueStart = start + marker.length();
        int valueEnd = valueStart;
        while (valueEnd < preview.length() && Character.isDigit(preview.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd <= valueStart) {
            return 0;
        }
        try {
            return Integer.parseInt(preview.substring(valueStart, valueEnd));
        } catch (NumberFormatException ignore) {
            traceSuppressed("attachment.archiveEntryCount", ignore);
            return 0;
        }
    }

    private static Map<String, Integer> archiveExtCounts(String preview) {
        String marker = "extensions=";
        if (preview == null) {
            return Map.of();
        }
        int start = preview.indexOf(marker);
        if (start < 0) {
            return Map.of();
        }
        int valueStart = start + marker.length();
        int valueEnd = preview.indexOf('\n', valueStart);
        String raw = (valueEnd < 0 ? preview.substring(valueStart) : preview.substring(valueStart, valueEnd)).trim();
        if (!raw.startsWith("{") || !raw.endsWith("}")) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        String body = raw.substring(1, raw.length() - 1).trim();
        if (body.isBlank()) {
            return out;
        }
        for (String part : body.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                out.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
            } catch (NumberFormatException ignore) {
                traceSuppressed("attachment.archiveExtCount", ignore);
            }
        }
        return out;
    }

    private static boolean looksLikeZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)
                && (bytes[3] == 4 || bytes[3] == 6 || bytes[3] == 8);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\u0000", "").trim();
        if (normalized.length() <= MAX_PREVIEW_CHARS) {
            return normalized;
        }
        int end = MAX_PREVIEW_CHARS;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))
                && Character.isLowSurrogate(normalized.charAt(end))) {
            end--;
        }
        return normalized.substring(0, end);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        return Path.of(name).getFileName().toString();
    }

    private NativeState detectNative() {
        if (canRunTesseract("tesseract")) {
            return new NativeState(true, "path");
        }
        String[] common = {
                "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"
        };
        for (String candidate : common) {
            if (Files.isRegularFile(Path.of(candidate)) && canRunTesseract(candidate)) {
                return new NativeState(true, "program-files");
            }
        }
        return new NativeState(false, "missing");
    }

    private boolean canRunTesseract(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(1200, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable ignore) {
            traceSuppressed("attachment.nativeProbe", ignore);
            return false;
        }
    }

    private TessdataState detectTessdata(String datapath, String tessdataPrefix, String language) {
        List<Path> candidates = new ArrayList<>();
        if (datapath != null && !datapath.isBlank()) {
            candidates.add(Path.of(datapath.trim()));
        }
        if (tessdataPrefix != null && !tessdataPrefix.isBlank()) {
            candidates.add(Path.of(tessdataPrefix.trim()));
        }
        candidates.add(Path.of("C:\\Program Files\\Tesseract-OCR\\tessdata"));
        candidates.add(Path.of("C:\\Program Files (x86)\\Tesseract-OCR\\tessdata"));

        List<String> languages = languages(language);
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            List<String> missing = missingLanguages(candidate, languages);
            if (missing.isEmpty()) {
                return new TessdataState(true, sourceLabel(candidate, datapath, tessdataPrefix), List.of());
            }
        }
        return new TessdataState(false, "missing", languages);
    }

    private static String sourceLabel(Path path, String datapath, String tessdataPrefix) {
        String p = path.toString();
        if (datapath != null && !datapath.isBlank() && p.equals(datapath.trim())) {
            return "ocr.datapath";
        }
        if (tessdataPrefix != null && !tessdataPrefix.isBlank() && p.equals(tessdataPrefix.trim())) {
            return "TESSDATA_PREFIX";
        }
        return "program-files";
    }

    private static List<String> languages(String language) {
        String value = (language == null || language.isBlank()) ? "eng+kor" : language;
        List<String> out = new ArrayList<>();
        for (String part : value.split("\\+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? List.of("eng") : out;
    }

    private static List<String> missingLanguages(Path dir, List<String> languages) {
        List<String> missing = new ArrayList<>();
        for (String language : languages) {
            if (!Files.isRegularFile(dir.resolve(language + ".traineddata"))) {
                missing.add(language);
            }
        }
        return missing;
    }

    private boolean getBoolean(String key, boolean fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private String getString(String key, String fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int getInt(String key, int fallback) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("attachment.propertyInt", ignore);
            return fallback;
        }
    }

    private long getLong(String key, long fallback) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("attachment.propertyLong", ignore);
            return fallback;
        }
    }

    private float getFloat(String key, float fallback) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("attachment.propertyFloat", ignore);
            return fallback;
        }
    }

    private record NativeState(boolean available, String source) {
    }

    private record TessdataState(boolean available, String source, List<String> missingLanguages) {
    }

    private record OcrResult(String text, List<Map<String, Object>> spans, String failureClass) {
        static OcrResult failure(String failureClass) {
            return new OcrResult("", List.of(), failureClass);
        }
    }
}
