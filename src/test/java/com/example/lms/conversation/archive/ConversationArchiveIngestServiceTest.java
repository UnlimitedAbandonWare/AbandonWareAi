package com.example.lms.conversation.archive;

import ai.abandonware.nova.orch.trace.OrchTrace;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ConversationArchiveIngestServiceTest {

    private VectorStoreService vectorStoreService;
    private ConversationArchiveIngestService service;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        vectorStoreService = mock(VectorStoreService.class);
        service = new ConversationArchiveIngestService(vectorStoreService, provider(null));
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void rejectsNonZipUpload() {
        MockMultipartFile txt = new MockMultipartFile(
                "files",
                "ConversationExport.txt",
                "text/plain",
                "Alice : hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.ingest(List.of(txt), "sid-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unsupported_archive_type");
        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void zipWithConversationTxtReturnsTreeAndSummaryOnly() throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_room.txt", """
                2026. 5. 27. AM 9:10, Alice : Roadmap user@example.com 010-1234-5678
                2026. 5. 27. AM 9:11, Bot : Summary: project archive
                2026. 5. 27. AM 9:12, Bob : Link https://github.com/example/repo
                2026. 5. 27. AM 9:13, Noise : <html><body><script>alert('body')</script></body></html>
                """);

        ConversationArchiveIngestReport report = service.ingest(List.of(zip), "session-raw");

        assertThat(report.ok()).isTrue();
        assertThat(report.tree()).containsExactly("ConversationExport_room.txt");
        assertThat(report.counts())
                .containsEntry("human_message", 1)
                .containsEntry("bot_summary", 1)
                .containsEntry("link_artifact", 1)
                .containsEntry("quarantined", 1);
        assertThat(report.ingestedCount()).isGreaterThanOrEqualTo(2);
        assertThat(report.toString())
                .doesNotContain("Roadmap", "user@example.com", "010-1234-5678", "alert('body')");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedChunksCallVectorEnqueueWithSafeMetadata() throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_room.txt", """
                2026. 5. 27. AM 9:10, Alice : Roadmap user@example.com 010-1234-5678
                2026. 5. 27. AM 9:11, Bob : Link https://github.com/example/repo
                """);

        service.ingest(List.of(zip), "sid-1");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService, atLeastOnce()).enqueue(anyString(), anyString(), textCaptor.capture(), metaCaptor.capture());

        assertThat(textCaptor.getAllValues().toString())
                .contains("***@***", "********")
                .doesNotContain("user@example.com", "010-1234-5678");
        assertThat(metaCaptor.getAllValues())
                .anySatisfy(meta -> {
                    assertThat(meta).containsEntry(VectorMetaKeys.META_SOURCE_TAG, "CONVERSATION_ARCHIVE");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_VERIFIED, "false");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_VERIFICATION_NEEDED, "false");
                })
                .anySatisfy(meta -> {
                    assertThat(meta).containsEntry(VectorMetaKeys.META_SOURCE_TAG, "CONVERSATION_ARCHIVE");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_DOC_TYPE, "KB");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_VERIFIED, "false");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
                    assertThat(meta).containsEntry(VectorMetaKeys.META_KB_DOMAIN, "conversation_archive");
                    assertThat(meta).containsKey(VectorMetaKeys.META_SCOPE_ANCHOR_KEY);
                });
    }

    @Test
    void longTechnicalDiscussionReachesVectorSubmission() throws Exception {
        assertHumanConversationSubmitted(ConversationNoiseClassifierCodeBoundaryTest.TECHNICAL_PROSE);
    }

    @Test
    void recurringArticleDiscussionReachesVectorSubmission() throws Exception {
        assertHumanConversationSubmitted(ConversationNoiseClassifierCodeBoundaryTest.ARTICLE_PROSE);
    }

    private void assertHumanConversationSubmitted(String message) throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_discussion.txt", "Alice : " + message);
        ConversationArchiveIngestReport report = service.ingest(List.of(zip), "sid-discussion");

        assertThat(report.counts()).containsEntry("human_message", 1);
        assertThat(report.counts().getOrDefault("quarantined", 0)).isZero();
        assertThat(report.ingestedCount()).isEqualTo(1);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), textCaptor.capture(), anyMap());
        assertThat(textCaptor.getValue()).contains(message);
    }

    @Test
    void quarantinedChunksAreSkipped() throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_noise.txt", """
                2026. 5. 27. AM 9:10, Noise : <html><body><script>alert('body')</script></body></html>
                2026. 5. 27. AM 9:11, Noise : aaa aaa aaa aaa aaa
                """);

        ConversationArchiveIngestReport report = service.ingest(List.of(zip), "sid-1");

        assertThat(report.ingestedCount()).isZero();
        assertThat(report.counts()).containsEntry("quarantined", 2);
        verify(vectorStoreService, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void traceAndOrchEventsAreSanitized() throws Exception {
        TraceStore.put("traceId", "trace-raw");
        TraceStore.put("requestId", "request-raw");
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_room.txt", """
                2026. 5. 27. AM 9:10, Alice : Secret roadmap user@example.com
                """);

        service.ingest(List.of(zip), "session-raw");

        assertThat(TraceStore.get("conversation.archive.humanMessageCount")).isEqualTo(1);
        Object events = TraceStore.get(OrchTrace.TRACE_KEY_EVENTS_V1);
        assertThat(String.valueOf(events))
                .contains("rag.ingest", "sessionIdHash")
                .doesNotContain("Secret roadmap", "user@example.com", "session-raw", "trace-raw", "request-raw");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Review https://unverified.invalid/claim",
            "Summary: an unverified assertion copied from a conversation"
    })
    @SuppressWarnings("unchecked")
    void linkOrSummaryShapeDoesNotVerifyUserArchiveContent(String message) throws Exception {
        MockMultipartFile zip = zipFile("conversation.zip", "ConversationExport_unverified.txt",
                "2026. 5. 27. AM 9:10, Alice : " + message);

        ConversationArchiveIngestReport report = service.ingest(List.of(zip), "sid-unverified");

        assertThat(report.ingestedCount()).isEqualTo(1);
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vectorStoreService).enqueue(anyString(), anyString(), anyString(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertThat(meta)
                .containsEntry(VectorMetaKeys.META_ORIGIN, "USER")
                .containsEntry(VectorMetaKeys.META_SOURCE_TAG, "CONVERSATION_ARCHIVE")
                .containsEntry(VectorMetaKeys.META_DOC_TYPE, "KB")
                .containsEntry(VectorMetaKeys.META_VERIFIED, "false")
                .containsEntry(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
        assertThat(meta.get("conversation.archive.kind")).isIn("link_artifact", "bot_summary");
    }

    @Test
    void invalidLaterEntryPublishesNoEarlierChunk() throws Exception {
        MockMultipartFile corrupt = corruptAfterValidEntry(
                "corrupt-later.zip",
                "ConversationExport_first.txt",
                "Alice : atomic archive prefix");

        assertThatThrownBy(() -> service.ingest(List.of(corrupt), "sid-atomic"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_zip_archive");

        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void invalidSecondArchivePublishesNoFirstArchiveChunk() throws Exception {
        MockMultipartFile valid = zipFile(
                "valid-first.zip",
                "ConversationExport_first.txt",
                "Alice : request wide archive atomicity");
        MockMultipartFile corrupt = corruptAfterValidEntry(
                "corrupt-second.zip",
                "ConversationExport_second.txt",
                "Bob : second archive prefix");

        assertThatThrownBy(() -> service.ingest(List.of(valid, corrupt), "sid-multi"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_zip_archive");

        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void rejectedArchiveThenValidRetryPublishesDeterministicChunkOnce() throws Exception {
        String entryName = "ConversationExport_retry.txt";
        String body = "Alice : deterministic retry chunk";
        MockMultipartFile corrupt = corruptAfterValidEntry("corrupt-retry.zip", entryName, body);

        assertThatThrownBy(() -> service.ingest(List.of(corrupt), "sid-retry"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_zip_archive");

        service.ingest(List.of(zipFile("valid-retry.zip", entryName, body)), "sid-retry");

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorStoreService, atLeastOnce())
                .enqueue(idCaptor.capture(), anyString(), anyString(), anyMap());
        assertThat(idCaptor.getAllValues()).hasSize(1).doesNotHaveDuplicates();
    }

    @Test
    void cumulativeDecompressedBytesRejectBeforePublish() throws Exception {
        MockMultipartFile oversized = zipWithRepeatedTextEntries(33, 2 * 1024 * 1024);

        assertThatThrownBy(() -> service.ingest(List.of(oversized), "sid-total"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("archive_total_too_large");

        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void exactCumulativeDecompressedLimitIsAccepted() throws Exception {
        MockMultipartFile exactLimit = zipWithRepeatedTextEntries(32, 2 * 1024 * 1024);

        ConversationArchiveIngestReport report = service.ingest(List.of(exactLimit), "sid-exact-total");

        assertThat(report.ingestedCount()).isPositive();
        verify(vectorStoreService, atLeastOnce())
                .enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void recordCapStillValidatesMalformedLaterEntryBeforePublish() throws Exception {
        MockMultipartFile corrupt = corruptAfterValidEntry(
                "record-cap-corrupt.zip",
                "ConversationExport_records.txt",
                conversationRecords(5000));

        assertThatThrownBy(() -> service.ingest(List.of(corrupt), "sid-record-cap"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_zip_archive");

        verifyNoInteractions(vectorStoreService);
    }

    @Test
    void entryCapStillValidatesMalformedLaterEntryBeforePublish() throws Exception {
        MockMultipartFile corrupt = corruptAfterIgnoredEntries(500);

        assertThatThrownBy(() -> service.ingest(List.of(corrupt), "sid-entry-cap"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_zip_archive");

        verifyNoInteractions(vectorStoreService);
    }

    private static MockMultipartFile zipFile(String fileName, String entryName, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("files", fileName, "application/zip", out.toByteArray());
    }

    private static MockMultipartFile corruptAfterValidEntry(
            String fileName,
            String validEntryName,
            String validBody) throws Exception {
        byte[] validBytes = validBody.getBytes(StandardCharsets.UTF_8);
        byte[] corruptBytes = "later-entry-payload-that-must-be-truncated".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeStoredEntry(zip, validEntryName, validBytes);
            writeStoredEntry(zip, "ConversationExport_later.txt", corruptBytes);
        }
        byte[] complete = out.toByteArray();
        int corruptBodyStart = indexOf(complete, corruptBytes);
        if (corruptBodyStart < 0) {
            throw new IllegalStateException("corrupt ZIP fixture payload not found");
        }
        byte[] truncated = Arrays.copyOf(
                complete,
                corruptBodyStart + Math.max(1, corruptBytes.length / 2));
        return new MockMultipartFile("files", fileName, "application/zip", truncated);
    }

    private static MockMultipartFile zipWithRepeatedTextEntries(int count, int entryBytes) throws Exception {
        byte[] body = exactSizedConversationBody(entryBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("ConversationExport_" + i + ".txt"));
                zip.write(body);
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("files", "cumulative.zip", "application/zip", out.toByteArray());
    }

    private static MockMultipartFile corruptAfterIgnoredEntries(int prefixCount) throws Exception {
        byte[] ignored = new byte[] { 'x' };
        byte[] corruptBytes = "entry-cap-payload-that-must-be-truncated".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < prefixCount; i++) {
                writeStoredEntry(zip, "ignored-" + i + ".bin", ignored);
            }
            writeStoredEntry(zip, "ignored-corrupt.bin", corruptBytes);
        }
        byte[] complete = out.toByteArray();
        int corruptBodyStart = indexOf(complete, corruptBytes);
        if (corruptBodyStart < 0) {
            throw new IllegalStateException("entry-cap corrupt ZIP fixture payload not found");
        }
        byte[] truncated = Arrays.copyOf(
                complete,
                corruptBodyStart + Math.max(1, corruptBytes.length / 2));
        return new MockMultipartFile("files", "entry-cap-corrupt.zip", "application/zip", truncated);
    }

    private static String conversationRecords(int count) {
        StringBuilder body = new StringBuilder(count * 40);
        for (int i = 0; i < count; i++) {
            body.append("Alice : useful archive record ").append(i).append('\n');
        }
        return body.toString();
    }

    private static byte[] exactSizedConversationBody(int size) {
        byte[] prefix = "Alice : cumulative archive bytes\n".getBytes(StandardCharsets.UTF_8);
        if (size < prefix.length) {
            throw new IllegalArgumentException("entry size too small");
        }
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) ' ');
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        return body;
    }

    private static void writeStoredEntry(ZipOutputStream zip, String name, byte[] body) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(body);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(body.length);
        entry.setCompressedSize(body.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(body);
        zip.closeEntry();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
