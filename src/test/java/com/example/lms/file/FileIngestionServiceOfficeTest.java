package com.example.lms.file;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIngestionServiceOfficeTest {

    private final FileIngestionService service = new FileIngestionService();

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void extractsTextFromOfficeOpenXmlAndHwpxWithoutGenericZipExpansion() throws Exception {
        assertContains(service.extractText("sample.docx", docxType(), zip(Map.of(
                "word/document.xml", "<w:document xmlns:w=\"w\"><w:body><w:t>Anchor Probe Office</w:t></w:body></w:document>"
        ))), "Anchor Probe Office");

        assertContains(service.extractText("deck.pptx", pptxType(), zip(Map.of(
                "ppt/slides/slide1.xml", "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><a:t>Plan DSL Slide</a:t></p:sld>"
        ))), "Plan DSL Slide");

        assertContains(service.extractText("sheet.xlsx", xlsxType(), zip(Map.of(
                "xl/sharedStrings.xml", "<sst xmlns=\"s\"><si><t>Vector K Sheet</t></si></sst>"
        ))), "Vector K Sheet");

        assertContains(service.extractText("doc.hwpx", "application/x-hwpml", zip(Map.of(
                "Contents/section0.xml", "<hp:section xmlns:hp=\"h\"><hp:t>HWPX 탐침</hp:t></hp:section>"
        ))), "HWPX 탐침");

        String archiveSummary = service.extractText("plain.zip", "application/zip", zip(Map.of(
                "word/document.xml", "<w:t>should not be read from generic zip</w:t>"
        )));
        assertNotNull(archiveSummary);
        assertTrue(archiveSummary.contains("### ARCHIVE TREE SUMMARY"), archiveSummary);
        assertTrue(archiveSummary.contains("sampledEntryCount=1"), archiveSummary);
        assertTrue(archiveSummary.contains("truncated=false"), archiveSummary);
        assertTrue(archiveSummary.contains("extensions={xml=1}"), archiveSummary);
        assertTrue(archiveSummary.contains("word/document.xml"), archiveSummary);
        assertFalse(archiveSummary.contains("should not be read from generic zip"), archiveSummary);
    }

    @Test
    void secureXmlParserDoesNotExposeExternalEntities() throws Exception {
        String xml = """
                <!DOCTYPE x [ <!ENTITY leak SYSTEM "file:///C:/Windows/win.ini"> ]>
                <w:document xmlns:w="w"><w:t>&leak;</w:t></w:document>
                """;

        String text = service.extractText("evil.docx", docxType(), zip(Map.of("word/document.xml", xml)));

        assertTrue(text == null || !text.contains("leak"));
        assertTrue(text == null || !text.toLowerCase().contains("windows"));
        assertEquals("fileIngestion.xmlText", TraceStore.get("file.ingestion.suppressed.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("file.ingestion.suppressed.fileIngestion.xmlText"));
        assertNotNull(TraceStore.get("file.ingestion.suppressed.errorType"));
        assertNotNull(TraceStore.get("file.ingestion.suppressed.fileIngestion.xmlText.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("win.ini"));
    }

    @Test
    void officeExtractionIsTruncatedToConfiguredLimit() throws Exception {
        String repeated = "A".repeat(60_000);
        String text = service.extractText("large.docx", docxType(), zip(Map.of(
                "word/document.xml", "<w:document xmlns:w=\"w\"><w:t>" + repeated + "</w:t></w:document>"
        )));

        assertNotNull(text);
        assertTrue(text.length() < 51_000);
        assertTrue(text.endsWith("[TRUNCATED]"));
    }

    @Test
    void textExtractionTruncationDoesNotSplitSupplementaryCharacters() {
        String marker = "\n[TRUNCATED]";
        String emoji = "\uD83D\uDE00";
        String splitBoundary = service.extractText(
                "split.txt",
                "text/plain",
                ("A".repeat(49_999) + emoji + "Z").getBytes(StandardCharsets.UTF_8));
        String completePairBoundary = service.extractText(
                "complete.txt",
                "text/plain",
                ("A".repeat(49_998) + emoji + "Z").getBytes(StandardCharsets.UTF_8));
        String bmpBoundary = service.extractText(
                "bmp.txt",
                "text/plain",
                ("A".repeat(50_000) + "Z").getBytes(StandardCharsets.UTF_8));
        String exactBoundary = service.extractText(
                "exact.txt",
                "text/plain",
                "A".repeat(50_000).getBytes(StandardCharsets.UTF_8));
        String shortSupplementary = service.extractText(
                "short.txt",
                "text/plain",
                ("start" + emoji + "end").getBytes(StandardCharsets.UTF_8));

        assertNotNull(splitBoundary);
        assertNotNull(completePairBoundary);
        assertNotNull(bmpBoundary);
        int splitMarkerIndex = splitBoundary.indexOf(marker);
        int completeMarkerIndex = completePairBoundary.indexOf(marker);
        int bmpMarkerIndex = bmpBoundary.indexOf(marker);

        assertAll(
                () -> assertEquals(49_999, splitMarkerIndex),
                () -> assertFalse(Character.isHighSurrogate(splitBoundary.charAt(splitMarkerIndex - 1))),
                () -> assertEquals(splitBoundary, new String(
                        splitBoundary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)),
                () -> assertEquals(50_000, completeMarkerIndex),
                () -> assertEquals(emoji, completePairBoundary.substring(49_998, 50_000)),
                () -> assertEquals(completePairBoundary, new String(
                        completePairBoundary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)),
                () -> assertEquals(50_000, bmpMarkerIndex),
                () -> assertEquals("A".repeat(50_000), bmpBoundary.substring(0, bmpMarkerIndex)),
                () -> assertEquals("A".repeat(50_000), exactBoundary),
                () -> assertEquals("start" + emoji + "end", shortSupplementary));
    }

    @Test
    void diagnosticsDoNotWriteRawFileNamesOrThrowableText() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/file/FileIngestionService.java"), StandardCharsets.UTF_8);

        assertFalse(source.contains("for {}: {}"));
        assertFalse(source.contains("for {}\", mimeType, fileName"));
        assertTrue(source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".toString()") || line.contains(".getMessage()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList()
                .isEmpty());
        assertTrue(source.contains("fileNameHash={} fileNameLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(fileName)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains(
                "[FileIngestion] PDF extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}"));
        assertTrue(source.contains(
                "[FileIngestion] extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}"));
        assertTrue(source.contains(
                "[FileIngestion] Archive tree extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}"));
        assertTrue(source.contains(
                "[FileIngestion] Office XML extraction failed fileNameHash={} fileNameLength={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
        assertTrue(source.contains("traceSuppressed(\"fileIngestion.archiveInt\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"fileIngestion.xmlText\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"fileIngestion.xmlFeature\", ignored);"));
        assertTrue(source.contains("TraceStore.put(\"file.ingestion.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"file.ingestion.suppressed.errorType\", safeErrorType);"));
        assertTrue(source.contains("TraceStore.put(\"file.ingestion.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"file.ingestion.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
    }

    private static void assertContains(String actual, String expected) {
        assertNotNull(actual);
        assertTrue(actual.contains(expected), actual);
        assertFalse(actual.contains("<w:t>"));
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

    private static String docxType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    private static String pptxType() {
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }

    private static String xlsxType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
