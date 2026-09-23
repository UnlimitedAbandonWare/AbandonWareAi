package com.example.lms.conversation.archive;

import com.example.lms.service.VectorMetaKeys;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class ConversationArchiveParserClassifierTest {

    private final ConversationExportParser parser = new ConversationExportParser();
    private final ConversationNoiseClassifier classifier = new ConversationNoiseClassifier();

    @Test
    void parsesNormalConversationLinesIntoRecords() {
        String text = """
                2026. 5. 27. AM 9:10, Alice : First message
                continuation line
                2026. 5. 27. AM 9:11, Bob : Second message
                """;

        List<ConversationMessageRecord> records = parser.parse("ConversationExport_test.txt", text);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).sender()).isEqualTo("Alice");
        assertThat(records.get(0).message()).contains("First message", "continuation line");
        assertThat(records.get(1).sender()).isEqualTo("Bob");
    }

    @Test
    void parserLimitDoesNotSplitSupplementaryCharacters() {
        String emoji = "\uD83D\uDE00";
        String splitBoundary = parser.parse(
                "synthetic.txt", "Alice: " + "A".repeat(11_999) + emoji + "Z")
                .get(0).message();
        String completePairBoundary = parser.parse(
                "synthetic.txt", "Alice: " + "A".repeat(11_998) + emoji + "Z")
                .get(0).message();
        String bmpBoundary = parser.parse(
                "synthetic.txt", "Alice: " + "A".repeat(12_000) + "Z")
                .get(0).message();
        String exactBoundary = parser.parse(
                "synthetic.txt", "Alice: " + "A".repeat(12_000))
                .get(0).message();
        String shortSupplementary = parser.parse(
                "synthetic.txt", "Alice: start" + emoji + "end")
                .get(0).message();

        assertAll(
                () -> assertThat(splitBoundary).hasSize(11_999),
                () -> assertThat(Character.isHighSurrogate(
                        splitBoundary.charAt(splitBoundary.length() - 1))).isFalse(),
                () -> assertThat(new String(
                        splitBoundary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                        .isEqualTo(splitBoundary),
                () -> assertThat(completePairBoundary).hasSize(12_000),
                () -> assertThat(completePairBoundary.substring(11_998, 12_000)).isEqualTo(emoji),
                () -> assertThat(new String(
                        completePairBoundary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                        .isEqualTo(completePairBoundary),
                () -> assertThat(bmpBoundary).isEqualTo("A".repeat(12_000)),
                () -> assertThat(exactBoundary).isEqualTo("A".repeat(12_000)),
                () -> assertThat(shortSupplementary).isEqualTo("start" + emoji + "end"));
    }

    @Test
    void detectsHtmlScriptBase64AndRepeatedJunkAsQuarantine() {
        assertThat(classifier.classifyText("<html><body><script>alert('x')</script></body></html>").kind())
                .isEqualTo(ConversationMessageKind.QUARANTINED);
        assertThat(classifier.classifyText("data:image/png;base64," + "A".repeat(220)).kind())
                .isEqualTo(ConversationMessageKind.QUARANTINED);
        assertThat(classifier.classifyText("aaa aaa aaa aaa aaa").kind())
                .isEqualTo(ConversationMessageKind.QUARANTINED);
    }

    @Test
    void detectsLinksAsLinkArtifact() {
        ConversationNoiseClassifier.Decision decision = classifier.classifyText("Please review https://github.com/example/repo");

        assertThat(decision.kind()).isEqualTo(ConversationMessageKind.LINK_ARTIFACT);
        assertThat(decision.ingestible()).isTrue();
    }

    @Test
    void masksEmailAndPhoneBeforeChunking() {
        ConversationTopicTimelineBuilder builder = new ConversationTopicTimelineBuilder();
        ConversationMessageRecord record = new ConversationMessageRecord(
                "ConversationExport.txt",
                1,
                "2026. 5. 27.",
                "Alice",
                "Contact user@example.com and 010-1234-5678 for details");
        ConversationNoiseClassifier.Decision decision =
                new ConversationNoiseClassifier.Decision(ConversationMessageKind.HUMAN_MESSAGE, "plain_text", true, 0.8);

        List<ConversationTopicTimelineBuilder.Chunk> chunks = builder.build(
                "sid-1",
                List.of(new ConversationTopicTimelineBuilder.ClassifiedRecord(record, decision)),
                1000,
                4);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
                .contains("***@***", "********")
                .doesNotContain("user@example.com", "010-1234-5678");
        assertThat(chunks.get(0).metadata())
                .containsEntry(VectorMetaKeys.META_SOURCE_PATH, "conversation_archive")
                .containsEntry("conversation.archive.source_path_hash", SafeRedactor.hashValue("ConversationExport.txt"))
                .containsEntry("conversation.archive.source_path_length", "ConversationExport.txt".length());
        assertThat(chunks.get(0).metadata().toString()).doesNotContain("ConversationExport.txt");
    }

    @Test
    void urlHostParseFailureLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/conversation/archive/ConversationTopicTimelineBuilder.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("traceSuppressed(\"conversationArchive.firstHost\", ignore);")
                .contains("TraceStore.put(\"conversation.archive.suppressed.\" + safeStage, true);");
    }
}
