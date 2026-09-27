package com.example.lms.service;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.file.FileIngestionService;
import com.example.lms.service.ocr.OcrService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentInspectionServiceOfficeTest {

    @Test
    void previewPreservesSurrogateBoundariesAndNormalization() throws Exception {
        Method method = AttachmentInspectionService.class.getDeclaredMethod("preview", String.class);
        method.setAccessible(true);
        String emoji = "\uD83D\uDE00";
        String prefix7999 = "a".repeat(7_999);

        String straddling = invokePreview(method, " \u0000" + prefix7999 + emoji + " ");
        String fullPair = invokePreview(method, "a".repeat(7_998) + emoji + "x");
        String bmpBoundary = invokePreview(method, prefix7999 + "z" + "x");

        assertEquals(prefix7999, straddling, "POS-PREVIEW-STRADDLE");
        assertFalse(Character.isHighSurrogate(straddling.charAt(straddling.length() - 1)));
        assertEquals(straddling, utf8RoundTrip(straddling));
        assertTrue(straddling.length() <= 8_000);
        assertEquals("a".repeat(7_998) + emoji, fullPair, "POS-PREVIEW-FULL-BMP pair");
        assertEquals(fullPair, utf8RoundTrip(fullPair));
        assertEquals(prefix7999 + "z", bmpBoundary, "POS-PREVIEW-FULL-BMP BMP");
        assertEquals(bmpBoundary, utf8RoundTrip(bmpBoundary));
        assertEquals("", invokePreview(method, null), "POS-PREVIEW-NORMALIZATION null");
        assertEquals("short", invokePreview(method, " \u0000short "), "POS-PREVIEW-NORMALIZATION NUL/trim");
        assertEquals("a".repeat(8_000), invokePreview(method, "a".repeat(8_000)),
                "POS-PREVIEW-NORMALIZATION exact limit");
    }

    @Test
    void emptyReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/AttachmentInspectionService.java"));

        assertFalse(source.contains("TraceStore.put(\"attachment.text.emptyReason\", failureClass);"));
        assertFalse(source.contains(
                "TraceStore.put(\"attachment.text.emptyReason\", SafeRedactor.safeMessage(failureClass, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"attachment.text.emptyReason\", SafeRedactor.traceLabelOrFallback(failureClass, \"unknown\"));"));
    }

    @Test
    void failSoftCatchBlocksLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/AttachmentInspectionService.java"));

        assertTrue(source.contains("traceSuppressed(\"attachment.extractText\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.ocr\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.imageMeta\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.readBytes\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.recordTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.archiveEntryCount\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.archiveExtCount\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.nativeProbe\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.propertyInt\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.propertyLong\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"attachment.propertyFloat\", ignore);"));
    }

    @Test
    void readinessUsesDisabledDefaultAndCanonicalOcrPropertiesFirst() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/AttachmentInspectionService.java"));

        assertTrue(source.contains("getBoolean(\"ocr.enabled\", false)"));
        assertTrue(source.contains("getFloat(\"ocr.min-confidence\", getFloat(\"ocr.minConfidence\", 0.65f))"));
        assertTrue(source.contains("getLong(\"ocr.timeout-ms\", getLong(\"ocr.timeoutMs\", 900L))"));
    }

    @Test
    void abandonwareOcrBeanIsGatedAndUsesCanonicalPropertyFallbacks() throws Exception {
        String basic = Files.readString(
                Path.of("main/java/com/abandonware/ai/service/ocr/BasicTesseractOcrService.java"));
        String nullService = Files.readString(
                Path.of("main/java/com/abandonware/ai/service/ocr/OcrNullService.java"));

        assertTrue(basic.contains("@ConditionalOnProperty(prefix = \"ocr\", name = \"enabled\", havingValue = \"true\")"));
        assertTrue(basic.contains("@Value(\"${ocr.timeout-ms:${ocr.timeoutMs:900}}\")"));
        assertTrue(basic.contains("@Value(\"${ocr.min-confidence:${ocr.minConfidence:0.65}}\")"));
        assertTrue(basic.contains("@Value(\"${ocr.datapath:${TESSDATA_PREFIX:}}\")"));
        assertTrue(nullService.contains("@ConditionalOnProperty(prefix = \"ocr\", name = \"enabled\", havingValue = \"false\", matchIfMissing = true)"));
    }

    @Test
    void inspectClassifiesOfficeDocumentsAsOfficeXmlWithoutOcr() throws Exception {
        AttachmentInspectionService service = new AttachmentInspectionService(
                new FileIngestionService(),
                null,
                new FixedObjectProvider<OcrService>(null),
                new FixedObjectProvider<DebugEventStore>(null),
                new MockEnvironment());
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "sample.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                zip(Map.of("word/document.xml",
                        "<w:document xmlns:w=\"w\"><w:body><w:t>Office evidence text</w:t></w:body></w:document>")));

        Map<String, Object> result = service.inspect(List.of(file), "session-1", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        Map<String, Object> inspected = files.get(0);
        assertEquals("office", inspected.get("type"));
        assertEquals("office-xml", inspected.get("source"));
        assertEquals(null, inspected.get("failureClass"));
        assertTrue(String.valueOf(inspected.get("textPreview")).contains("Office evidence text"));
        assertFalse(String.valueOf(inspected).contains("<w:t>"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ocr = (Map<String, Object>) inspected.get("ocr");
        assertEquals(false, ocr.get("attempted"));
    }

    @Test
    void inspectClassifiesZipAsArchiveTreeWithoutOcrOrBodyExpansion() throws Exception {
        AttachmentInspectionService service = new AttachmentInspectionService(
                new FileIngestionService(),
                null,
                new FixedObjectProvider<OcrService>(null),
                new FixedObjectProvider<DebugEventStore>(null),
                new MockEnvironment());
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "docs.zip",
                "application/zip",
                zip(Map.of(
                        "docs/report.txt", "internal body must not appear",
                        "slides/deck.pptx", "ppt bytes")));

        Map<String, Object> result = service.inspect(List.of(file), "session-zip", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        Map<String, Object> inspected = files.get(0);
        assertEquals("archive", inspected.get("type"));
        assertEquals("archive-tree", inspected.get("source"));
        assertEquals(null, inspected.get("failureClass"));
        String preview = String.valueOf(inspected.get("textPreview"));
        assertTrue(preview.contains("sampledEntryCount=2"), preview);
        assertTrue(preview.contains("truncated=false"), preview);
        assertTrue(preview.contains("docs/report.txt"), preview);
        assertFalse(preview.contains("internal body must not appear"), preview);

        @SuppressWarnings("unchecked")
        Map<String, Object> ocr = (Map<String, Object>) inspected.get("ocr");
        assertEquals(false, ocr.get("attempted"));
    }

    @Test
    void inspectPdfWithoutTextLayerReportsOcrRequiredWhenNativeUnavailable() throws Exception {
        AttachmentInspectionService service = new AttachmentInspectionService(
                new FileIngestionService(),
                null,
                new FixedObjectProvider<OcrService>(null),
                new FixedObjectProvider<DebugEventStore>(null),
                new MockEnvironment());
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "scan.pdf",
                "application/pdf",
                blankPdf());

        Map<String, Object> result = service.inspect(List.of(file), "session-pdf", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        Map<String, Object> inspected = files.get(0);
        assertEquals("pdf", inspected.get("type"));
        assertEquals("text_layer_empty_ocr_required", inspected.get("failureClass"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ocr = (Map<String, Object>) inspected.get("ocr");
        assertEquals(false, ocr.get("attempted"));
    }

    @Test
    void averageConfidenceIgnoresNonFiniteSpanValues() throws Exception {
        Method method = AttachmentInspectionService.class.getDeclaredMethod("averageConfidence", List.class);
        method.setAccessible(true);

        double average = (Double) method.invoke(null, List.of(
                Map.of("confidence", Double.NaN),
                Map.of("confidence", 0.75d)));

        assertEquals(0.75d, average);
    }

    private static String invokePreview(Method method, String text) throws Exception {
        return (String) method.invoke(null, new Object[]{text});
    }

    private static String utf8RoundTrip(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] blankPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(out);
        }
        return out.toByteArray();
    }

    private record FixedObjectProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override
        public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override
        public T getIfUnique() throws BeansException {
            return value;
        }

        @Override
        public T getObject() throws BeansException {
            return value;
        }
    }
}
