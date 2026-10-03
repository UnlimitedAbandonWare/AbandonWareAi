package com.example.lms.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class FileIngestionStructuredDocumentTest {
    private final FileIngestionService parser = new FileIngestionService();

    @AfterEach void clearTrace() { com.example.lms.search.TraceStore.clear(); }

    @Test void markdownPreservesTailAndExactDecodedSpansBeyondLegacyPreview() {
        String text = "# Report\n" + "stable line\n".repeat(5_000) + "## Done\nDo not publish.\n";
        var parsed = parse("report.md", text);
        assertEquals("READY", parsed.state());
        assertEquals(text, parsed.units().stream().map(FileIngestionService.TextUnit::text)
                .collect(java.util.stream.Collectors.joining()));
        var last = parsed.units().get(parsed.units().size() - 1);
        assertTrue(last.text().contains("Do not publish."));
        assertTrue(last.lineStart() > 300);
        for (var unit : parsed.units()) {
            assertEquals(unit.text(), text.substring(unit.startOffset(), unit.endOffset()));
            assertTrue(unit.text().length() <= 2_000);
        }
        assertTrue(parser.extractText("report.md", "text/plain", text.getBytes(StandardCharsets.UTF_8))
                .endsWith("[TRUNCATED]"));
    }

    @Test void utf8AndUtf16BomAreDecodedWithoutInventedByteOffsets() {
        var utf8 = parse("report.txt", "\ufefffirst\nsecond");
        assertEquals("first\nsecond", utf8.units().get(0).text());
        byte[] utf16 = "\ufefffirst\nsecond".getBytes(StandardCharsets.UTF_16LE);
        var parsed = parser.extractDocument("report.txt", "text/plain", utf16, List.of());
        assertEquals("first\nsecond", parsed.units().get(0).text());
        assertEquals(1, parsed.units().get(0).lineStart());
        assertEquals(2, parsed.units().get(0).lineEnd());
    }

    @Test void jsonHasValidatedPointersAndOriginalSpans() {
        String text = "{\n \"owner\": \"untrusted\",\n \"items\": [7, true]\n}";
        var parsed = parse("report.json", text);
        assertEquals("READY", parsed.state());
        assertEquals(List.of("/owner", "/items/0", "/items/1"),
                parsed.units().stream().map(FileIngestionService.TextUnit::jsonPointer).toList());
        assertEquals(2, parsed.units().get(0).lineStart());
        for (var unit : parsed.units()) assertEquals(unit.text(), text.substring(unit.startOffset(), unit.endOffset()));
    }

    @Test void malformedAndTrailingJsonNeverBecomePlainTextFallback() {
        assertEquals("CORRUPT", parse("bad.json", "{\"a\":").state());
        assertEquals("CORRUPT", parse("bad.json", "{\"a\":1} {\"b\":2}").state());
    }

    @Test void jsonlKeepsValidRecordsWithTheirOriginalLineAndOffset() {
        String text = "{\"a\":1}\n{bad}\n{\"b\":2}\n";
        var parsed = parse("events.jsonl", text);
        assertEquals("PARTIAL", parsed.state());
        assertEquals(1, parsed.invalidRecordCount());
        assertEquals(List.of(1, 3), parsed.units().stream().map(FileIngestionService.TextUnit::lineStart).toList());
        assertEquals("/b", parsed.units().get(1).jsonPointer());
        for (var unit : parsed.units()) assertEquals(unit.text(), text.substring(unit.startOffset(), unit.endOffset()));
    }

    @Test void binaryAndMalformedEncodingDoNotPassAsText() {
        for (byte[] bytes : List.of(new byte[]{0, 1, 2}, new byte[]{(byte) 0xc3, 0x28}, new byte[]{'M', 'Z', 0})) {
            var parsed = parser.extractDocument("report.txt", "text/plain", bytes, List.of());
            assertEquals("CORRUPT", parsed.state());
            assertTrue(parsed.units().isEmpty());
        }
    }

    @Test void configuredDocumentByteLimitIsEnforced() {
        ReflectionTestUtils.setField(parser, "environment", new MockEnvironment()
                .withProperty("attachments.documents.maxBytes", "8"));
        assertEquals("LIMIT_EXCEEDED", parse("report.md", "123456789").state());
    }

    @Test void koreanAndSurrogatePairsKeepSpansWithoutWhitespaceTokenClaims() {
        String text = "\uac00".repeat(12_000) + "\ud83d\ude00".repeat(2_001);
        var parsed = parse("report.txt", text);
        assertEquals("READY", parsed.state());
        assertEquals(text, parsed.units().stream().map(FileIngestionService.TextUnit::text)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(parsed.units().stream().allMatch(u -> !Character.isHighSurrogate(u.text().charAt(u.text().length() - 1))));
    }

    @Test void selectsSourcePastTheThreeHundredEntryPreviewWithoutExpandingOthers() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 3_009; i++) entries.put("docs/note-" + i + ".txt", "unselected body");
        entries.put("main/Target.java", "class Target {\n int answer = 42;\n}\n");
        var parsed = parser.extractDocument("source.zip", "application/zip", zip(entries), List.of("main/Target.java"));
        assertEquals("PARTIAL", parsed.state());
        assertEquals(3_010, parsed.inspectedEntryCount());
        assertEquals(1, parsed.bodyReadCount());
        assertEquals(3_009, parsed.omittedEntryCount());
        assertEquals(300, parsed.archiveMembers().size());
        assertEquals("main/Target.java", parsed.units().get(0).memberPath());
        assertTrue(parsed.units().get(0).text().contains("answer = 42"));
        assertFalse(parsed.units().stream().anyMatch(u -> u.text().contains("unselected body")));
    }

    @Test void zipListIsExplicitlyNotParsedBody() throws Exception {
        var parsed = parser.extractDocument("source.zip", "application/zip",
                zip(Map.of("report.md", "private body")), List.of());
        assertEquals("PARTIAL", parsed.state());
        assertEquals("ARCHIVE_LIST_ONLY", parsed.reasonCode());
        assertEquals(0, parsed.bodyReadCount());
        assertTrue(parsed.units().isEmpty());
    }

    @Test void refusesTraversalAbsoluteDriveAndCaseCollisionArchives() throws Exception {
        for (String path : List.of("../report.txt", "/report.txt", "C:/report.txt", "a\\report.txt")) {
            var parsed = parser.extractDocument("source.zip", "application/zip", zip(Map.of(path, "body")), List.of(path));
            assertEquals("CORRUPT", parsed.state(), path);
            assertTrue(parsed.units().isEmpty());
        }
        assertEquals("CORRUPT", parser.extractDocument("source.zip", "application/zip",
                zip(Map.of("A.txt", "one", "a.txt", "two")), List.of("A.txt")).state());
    }

    @Test void refusesSensitiveNestedAndUnsupportedSelectedMembers() throws Exception {
        for (String path : List.of(".env", ".git/config", "private/key.pem", "browser/storage-state.json",
                "application-local.yml", "node_modules/a.js", "nested.zip")) {
            var parsed = parser.extractDocument("source.zip", "application/zip", zip(Map.of(path, "body")), List.of(path));
            assertEquals("CORRUPT", parsed.state(), path);
            assertTrue(parsed.units().isEmpty());
            assertEquals(0, parsed.bodyReadCount());
        }
    }

    @Test void rejectsSymlinksAndEncryptedEntriesAtCentralDirectoryBoundary() throws Exception {
        for (boolean symlink : List.of(true, false)) {
            byte[] bytes = zip(Map.of("report.txt", "body"));
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int cen = centralOffset(bytes);
            if (symlink) {
                b.putShort(cen + 4, (short) 0x0314);
                b.putInt(cen + 38, 0xa1ff0000);
            } else b.putShort(cen + 8, (short) (b.getShort(cen + 8) | 1));
            assertEquals("CORRUPT", parser.extractDocument("source.zip", "application/zip", bytes, List.of("report.txt")).state());
        }
    }

    @Test void boundsSelectedBodyBytesAndCount() throws Exception {
        byte[] bomb = zip(Map.of("big.txt", "a".repeat(1_048_577)));
        assertEquals("LIMIT_EXCEEDED", parser.extractDocument("source.zip", "application/zip", bomb, List.of("big.txt")).state());
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) entries.put(i + ".txt", "body");
        assertEquals("LIMIT_EXCEEDED", parser.extractDocument("source.zip", "application/zip",
                zip(entries), List.copyOf(entries.keySet())).state());
    }

    @Test void missingSelectedMemberAndFakeZipAreHonestFailures() throws Exception {
        assertEquals("CORRUPT", parser.extractDocument("source.zip", "application/zip",
                zip(Map.of("a.txt", "body")), List.of("missing.txt")).state());
        assertEquals("CORRUPT", parse("fake.zip", "not zip bytes").state());
    }

    @Test void docxRetainsParagraphAndTableOrderWithParagraphLocations() throws Exception {
        String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>first</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>table cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "<w:p><w:r><w:t>last</w:t></w:r></w:p></w:body></w:document>";
        var parsed = parser.extractDocument("report.docx", "application/octet-stream",
                zip(Map.of("word/document.xml", xml)), List.of());
        assertEquals("READY", parsed.state());
        assertEquals(List.of("first", "table cell", "last"), parsed.units().stream().map(FileIngestionService.TextUnit::text).toList());
        assertEquals(List.of(1, 2, 3), parsed.units().stream().map(FileIngestionService.TextUnit::paragraphIndex).toList());
        assertTrue(parsed.units().stream().allMatch(u -> u.locatorType().equals("PARAGRAPH") && u.lineStart() == 0));
    }

    @Test void docxExternalEntitiesAreRejected() throws Exception {
        String xml = "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///must-not-read'>]><a>&x;</a>";
        assertEquals("CORRUPT", parser.extractDocument("report.docx", "",
                zip(Map.of("word/document.xml", xml)), List.of()).state());
    }

    private FileIngestionService.DocumentExtraction parse(String name, String text) {
        return parser.extractDocument(name, "", text.getBytes(StandardCharsets.UTF_8), List.of());
    }

    @Test void excerptRetainsExactOffsetsLinesAndWholeSurrogates() {
        String original = "alpha\n\uD55C\uAE00\uD83D\uDE00\nlast";
        var unit = parser.extractDocument("report.md", "text/markdown", original.getBytes(StandardCharsets.UTF_8), List.of()).units().get(0);
        var head = unit.excerpt(9, false);
        var tail = unit.excerpt(7, true);
        assertEquals("alpha\n\uD55C\uAE00", head.text());
        assertEquals(1, head.lineStart());
        assertEquals(2, head.lineEnd());
        assertEquals("\uD83D\uDE00\nlast", tail.text());
        assertEquals(2, tail.lineStart());
        assertEquals(3, tail.lineEnd());
        for (var part : List.of(head, tail)) {
            assertEquals(part.text(), original.substring(part.startOffset(), part.endOffset()));
            assertFalse(Character.isLowSurrogate(part.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(part.text().charAt(part.text().length() - 1)));
        }
    }

    @Test void centralDirectoryHardLimitIsIndependentOfThePreviewLimit() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 10_001; i++) entries.put(i + ".txt", "");
        var parsed = parser.extractDocument("source.zip", "application/zip", zip(entries), List.of("10000.txt"));
        assertEquals("LIMIT_EXCEEDED", parsed.state());
        assertEquals("ARCHIVE_ENTRY_LIMIT", parsed.reasonCode());
        assertEquals(0, parsed.bodyReadCount());
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static int centralOffset(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= bytes.length - 4; i++) if (b.getInt(i) == 0x02014b50) return i;
        throw new AssertionError("fixture central directory missing");
    }
}
