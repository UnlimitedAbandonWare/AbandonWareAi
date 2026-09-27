package com.example.lms.service.vector;

import com.example.lms.service.VectorMetaKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkingServiceTest {

    @Test
    void splitCreatesPhysicalOverlapWithoutTextRepair() throws Exception {
        DocumentChunkingService service = new DocumentChunkingService();
        set(service, "enabled", true);
        set(service, "chunkSizeChars", 128);
        set(service, "overlapChars", 12);
        set(service, "minSplitChars", 129);

        String text = "A".repeat(128) + "B".repeat(128) + "C".repeat(32);
        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "doc-1"));

        assertEquals(3, chunks.size());
        assertEquals(128, chunks.get(0).text().length());
        assertTrue(chunks.get(1).text().startsWith(chunks.get(0).text().substring(116)));
        assertEquals(0, chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_INDEX));
        assertEquals(3, chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_COUNT));
        assertEquals(12, chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_OVERLAP));
        assertEquals("doc-1#0", chunks.get(0).metadata().get(VectorMetaKeys.META_CHUNK_ID));
    }

    @Test
    void splitPrefersParagraphBoundaryWhenAvailable() throws Exception {
        DocumentChunkingService service = new DocumentChunkingService();
        set(service, "enabled", true);
        set(service, "chunkSizeChars", 180);
        set(service, "overlapChars", 20);
        set(service, "minSplitChars", 181);

        String first = "alpha paragraph. ".repeat(10);
        String second = "beta paragraph. ".repeat(10);
        String text = first + "\n\n" + second;

        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "doc-2"));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).text().endsWith("\n\n"));
    }

    @Test
    void splitNeverBisectsSupplementaryCodePointAtChunkEnd() throws Exception {
        DocumentChunkingService service = configuredService(true, 1);
        String text = "A".repeat(127) + "\uD83D\uDE00" + "B".repeat(128);

        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "unicode-end"));

        assertMonotonicCoverage(text, chunks);
        assertValidSurrogateBoundaries(chunks);
    }

    @Test
    void splitHonorsConfiguredMaximumAndBoundedOverlapAtSupplementaryBoundaries() throws Exception {
        DocumentChunkingService service = configuredService(true, 1);
        List<String> texts = List.of(
                privateUseText(127, 0xE000) + "\uD83D\uDE00" + privateUseText(140, 0xE100),
                privateUseText(126, 0xE200) + "\uD83D\uDE00" + privateUseText(140, 0xE300));

        for (String text : texts) {
            List<DocumentChunkingService.Chunk> chunks =
                    service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "unicode-hard-limit"));

            assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 128),
                    "a Unicode-safe adjustment must never exceed the configured hard maximum");
            assertMonotonicCoverage(text, chunks);
            assertValidSurrogateBoundaries(chunks);
            assertOverlapWithinCodeUnitAdjustment(text, chunks, 1);
            assertTrue(chunks.stream().allMatch(chunk -> Integer.valueOf(1).equals(
                    chunk.metadata().get(VectorMetaKeys.META_CHUNK_OVERLAP))));
        }
    }

    @Test
    void splitNeverStartsOverlapInsideSupplementaryCodePoint() throws Exception {
        DocumentChunkingService service = configuredService(true, 1);
        List<String> texts = List.of(
                "\uD83D\uDE00" + "A".repeat(255),
                "A".repeat(128) + "\uD83D\uDE00",
                "A".repeat(126) + "\uD83D\uDE00\uD83D\uDE00" + "B".repeat(128));

        for (String text : texts) {
            List<DocumentChunkingService.Chunk> chunks =
                    service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "unicode-overlap"));
            assertTrue(chunks.size() <= text.length());
            assertValidSurrogateBoundaries(chunks);
        }
    }

    @Test
    void splitPreservesBmpDisabledAndMalformedSurrogateControls() throws Exception {
        DocumentChunkingService bmpService = configuredService(true, 1);
        List<DocumentChunkingService.Chunk> bmpChunks = bmpService.split(
                "가".repeat(257),
                Map.of(VectorMetaKeys.META_DOC_ID, "bmp-control"));
        assertEquals(List.of(128, 128, 3), bmpChunks.stream().map(chunk -> chunk.text().length()).toList());

        DocumentChunkingService disabledService = configuredService(false, 1);
        String disabledText = "A".repeat(127) + "\uD83D\uDE00" + "B".repeat(128);
        List<DocumentChunkingService.Chunk> disabledChunks = disabledService.split(
                disabledText,
                Map.of(VectorMetaKeys.META_DOC_ID, "disabled-control"));
        assertEquals(1, disabledChunks.size());
        assertEquals(disabledText, disabledChunks.get(0).text());

        DocumentChunkingService malformedService = configuredService(true, 0);
        for (String malformed : List.of(
                "A".repeat(127) + "\uD83D" + "B".repeat(128),
                "A".repeat(127) + "\uDE00" + "B".repeat(128))) {
            List<DocumentChunkingService.Chunk> chunks = malformedService.split(
                    malformed,
                    Map.of(VectorMetaKeys.META_DOC_ID, "malformed-control"));
            assertTrue(chunks.size() <= malformed.length());
            assertEquals(malformed, String.join("", chunks.stream().map(DocumentChunkingService.Chunk::text).toList()));
        }
    }

    @Test
    void splitKeepsSemanticBoundaryNearSupplementaryText() throws Exception {
        DocumentChunkingService service = configuredService(true, 1, 180);
        String text = "A".repeat(170) + ". " + "\uD83D\uDE00" + "B".repeat(160);

        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "unicode-semantic"));

        assertTrue(chunks.get(0).text().endsWith("."));
        assertValidSurrogateBoundaries(chunks);
    }

    @Test
    void enabledDefaultThresholdNeverOverridesConfiguredHardMaximum() throws Exception {
        DocumentChunkingService service = new DocumentChunkingService();
        set(service, "enabled", true);
        set(service, "chunkSizeChars", 1_000);
        set(service, "overlapChars", 120);
        set(service, "minSplitChars", 1_400);
        String text = privateUseText(1_200, 0xE000);

        List<DocumentChunkingService.Chunk> chunks =
                service.split(text, Map.of(VectorMetaKeys.META_DOC_ID, "strict-default-ceiling"));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 1_000));
        assertMonotonicCoverage(text, chunks);
        assertValidSurrogateBoundaries(chunks);
        assertOverlapWithinCodeUnitAdjustment(text, chunks, 120);
    }

    private static DocumentChunkingService configuredService(boolean enabled, int overlap) throws Exception {
        return configuredService(enabled, overlap, 128);
    }

    private static DocumentChunkingService configuredService(boolean enabled, int overlap, int chunkSize) throws Exception {
        DocumentChunkingService service = new DocumentChunkingService();
        set(service, "enabled", enabled);
        set(service, "chunkSizeChars", chunkSize);
        set(service, "overlapChars", overlap);
        set(service, "minSplitChars", chunkSize + 1);
        return service;
    }

    private static void assertValidSurrogateBoundaries(List<DocumentChunkingService.Chunk> chunks) {
        for (DocumentChunkingService.Chunk chunk : chunks) {
            String value = chunk.text();
            assertFalse(value.isEmpty());
            assertFalse(Character.isLowSurrogate(value.charAt(0)));
            assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
        }
    }

    private static void assertMonotonicCoverage(String source, List<DocumentChunkingService.Chunk> chunks) {
        int previousStart = -1;
        int coveredEnd = 0;
        for (DocumentChunkingService.Chunk chunk : chunks) {
            int latestStartWithoutGap = Math.min(coveredEnd, source.length() - chunk.text().length());
            int chunkStart = previousStart < 0
                    ? source.indexOf(chunk.text())
                    : source.lastIndexOf(chunk.text(), latestStartWithoutGap);
            assertTrue(chunkStart >= 0, "chunk must map back to the source");
            assertTrue(chunkStart > previousStart, "chunk starts must advance");
            assertTrue(chunkStart <= coveredEnd, "chunks must not leave a source gap");
            coveredEnd = Math.max(coveredEnd, chunkStart + chunk.text().length());
            previousStart = chunkStart;
        }
        assertEquals(source.length(), coveredEnd);
        assertTrue(chunks.size() <= source.length());
    }

    private static void assertOverlapWithinCodeUnitAdjustment(
            String source,
            List<DocumentChunkingService.Chunk> chunks,
            int requestedOverlap) {
        int previousStart = source.indexOf(chunks.get(0).text());
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunkingService.Chunk previous = chunks.get(i - 1);
            int nextStart = source.indexOf(chunks.get(i).text(), previousStart + 1);
            int actualOverlap = previousStart + previous.text().length() - nextStart;
            assertTrue(actualOverlap == requestedOverlap || actualOverlap == requestedOverlap - 1,
                    "actual overlap must be nominal or one UTF-16 unit smaller at a code-point boundary");
            previousStart = nextStart;
        }
    }

    private static String privateUseText(int length, int startCodeUnit) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append((char) (startCodeUnit + i));
        }
        return value.toString();
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
