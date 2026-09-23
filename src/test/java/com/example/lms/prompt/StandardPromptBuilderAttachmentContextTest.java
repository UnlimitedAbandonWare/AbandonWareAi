package com.example.lms.prompt;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardPromptBuilderAttachmentContextTest {

    private final StandardPromptBuilder builder = new StandardPromptBuilder();

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void rendersLocalDocsAsDocumentEvidenceAndKeepsOtherListsNullSafe() {
        Document doc = Document.from(
                "Ontology document evidence\n<w:t>raw xml tag removed</w:t>",
                new Metadata(Map.of("source", "attachment", "attachmentType", "office")));
        PromptContext ctx = PromptContext.builder()
                .localDocs(List.of(doc))
                .build();

        String prompt = builder.build(List.of(ctx), "문서 근거를 요약해줘");

        assertTrue(prompt.contains("### LOCAL DOCUMENTS"), prompt);
        assertTrue(prompt.contains("[D1]"), prompt);
        assertTrue(prompt.contains("Ontology document evidence"), prompt);
        assertFalse(prompt.contains("<w:t>"), prompt);
        assertTrue(prompt.contains("### SEARCH RESULTS"), prompt);
        assertTrue(prompt.contains("### USER QUESTION"), prompt);
        assertEquals(1, TraceStore.get("prompt.localDocsRenderedCount"));
    }

    @Test
    void archiveSummaryDoesNotExposeZipInternalBodies() {
        Document doc = Document.from(
                """
                ### ARCHIVE TREE SUMMARY
                sampledEntryCount=1
                truncated=false
                extensions={txt=1}
                tree:
                - [file] docs/report.txt
                """,
                new Metadata(Map.of("source", "attachment", "attachmentType", "archive")));
        PromptContext ctx = PromptContext.builder()
                .localDocs(List.of(doc))
                .build();

        String prompt = builder.build(List.of(ctx), "zip 구조만 보여줘");

        assertTrue(prompt.contains("[D1]"), prompt);
        assertTrue(prompt.contains("docs/report.txt"), prompt);
        assertFalse(prompt.contains("internal body must not appear"), prompt);
    }
}
