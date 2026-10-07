package com.example.lms.prompt;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardPromptBuilderEvidenceMetadataTest {

    private final StandardPromptBuilder builder = new StandardPromptBuilder();

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void duplicateCurrentWebLocatorDoesNotConsumeSelectedBodyBudget() {
        String relation = "Cobalt signature weapon is Azure Lantern";
        String qualifier = "unofficial community estimate dated 2026-10-01";
        String basis = "background ".repeat(25) + relation + "; " + qualifier + " ";
        String selected = basis + "x".repeat(480 - basis.length());
        for (String url : List.of("https://docs.example/x", longSourceUrl())) {
            Content web = webWithTrailer(selected, url);
            PromptContext ctx = PromptContext.builder().web(List.of(web))
                    .evidence(List.of(webEvidence(url))).build();

            String prompt = builder.build(ctx);
            String snippet = webSnippet(prompt);

            assertTrue(snippet.contains(relation), "selected relation lost for URL length " + url.length());
            assertTrue(snippet.contains(qualifier), "selected qualifier lost for URL length " + url.length());
            assertTrue(snippet.startsWith(selected));
            assertTrue(snippet.length() <= 512);
            assertTrue(prompt.contains("[W1] kind=WEB; title=Cobalt; source=" + url));
        }
    }

    @Test
    void unmatchedOrUnrenderedLocatorsKeepLegacySnippetAndSource() {
        String url = longSourceUrl();
        String text = "selected body\n\n[출처] " + url;
        Content web = webWithTrailer("selected body", url);
        for (PromptContext ctx : List.of(
                PromptContext.builder().web(List.of(web)).build(),
                PromptContext.builder().web(List.of(web)).evidence(List.of(webEvidence(url + "/other"))).build(),
                PromptContext.builder().web(List.of(Content.from(TextSegment.from(text))))
                        .evidence(List.of(webEvidence(url))).build(),
                PromptContext.builder().web(List.of(Content.from(TextSegment.from(text,
                        Metadata.from(Map.of("url", url + "/other", "source", url))))))
                        .evidence(List.of(webEvidence(url))).build(),
                PromptContext.builder().web(List.of(web)).evidence(List.of(new RagEvidenceMetadata(
                        null, "WEB", "Cobalt", url, null, null, null, 1, null, null))).build(),
                PromptContext.builder().web(List.of(web)).evidence(List.of(new RagEvidenceMetadata(
                        "W2", "WEB", "Cobalt", url, null, null, null, 1, null, null))).build(),
                PromptContext.builder().web(List.of(web)).evidence(List.of(new RagEvidenceMetadata(
                        "W1", "RAG", "Cobalt", url, null, null, null, 1, null, null))).build())) {
            assertEquals(text, webSnippet(builder.build(ctx)));
        }
        String crossContext = builder.build(List.of(PromptContext.builder().web(List.of(web)).build(),
                PromptContext.builder().evidence(List.of(webEvidence(url))).build()), "Cobalt?");
        assertEquals(text, webSnippet(crossContext));
        String rag = builder.build(PromptContext.builder().rag(List.of(web))
                .evidence(List.of(webEvidence(url))).build());
        assertTrue(rag.contains("[V1] " + text));
    }

    @Test
    void onlyExactTerminalTrailerIsRedundantAndTruncatedMetadataIsInsufficient() {
        String url = longSourceUrl();
        String body = "quoted [출처] " + url + " inside the selected body";
        String prompt = builder.build(PromptContext.builder().web(List.of(webWithTrailer(body, url)))
                .evidence(List.of(webEvidence(url))).build());
        assertEquals(body, webSnippet(prompt));
        String nonterminal = body + "\n\n[출처] " + url + "\nadditional qualifier";
        prompt = builder.build(PromptContext.builder().web(List.of(Content.from(TextSegment.from(nonterminal,
                Metadata.from(Map.of("url", url, "source", url))))))
                .evidence(List.of(webEvidence(url))).build());
        assertEquals(nonterminal, webSnippet(prompt));
        String oversizedUrl = url + "x".repeat(200);
        String oversizedText = "selected body\n\n[출처] " + oversizedUrl;
        prompt = builder.build(PromptContext.builder().web(List.of(webWithTrailer("selected body", oversizedUrl)))
                .evidence(List.of(webEvidence(oversizedUrl))).build());
        assertEquals(oversizedText, webSnippet(prompt), "a truncated metadata locator cannot replace the trailer");
    }

    private static String longSourceUrl() {
        return "https://docs.example.test/articles/" + "chapter-2026-".repeat(8) + "index.html";
    }

    private static Content webWithTrailer(String body, String url) {
        return Content.from(TextSegment.from(body + "\n\n[출처] " + url,
                Metadata.from(Map.of("url", url, "source", url))));
    }

    private static RagEvidenceMetadata webEvidence(String url) {
        return new RagEvidenceMetadata("W1", "WEB", "Cobalt", url, null, null, null, 1, null, null);
    }

    private static String webSnippet(String prompt) {
        return prompt.split("### SEARCH RESULTS\\n", 2)[1].split("\\n### USER QUESTION", 2)[0].substring(5).strip();
    }

    @Test
    void rendersCitableEvidenceMetadataBeforeSearchResults() {
        Content web = Content.from(TextSegment.from(
                "alpha searchable body",
                Metadata.from(Map.of("title", "Alpha", "url", "https://example.com/a"))));
        PromptContext ctx = PromptContext.builder()
                .web(List.of(web))
                .evidence(List.of(new RagEvidenceMetadata(
                        "W1",
                        "WEB",
                        "Alpha",
                        "https://example.com/a",
                        null,
                        5,
                        null,
                        1,
                        0.91d,
                        "score")))
                .build();

        String prompt = builder.build(List.of(ctx), "alpha?");

        assertTrue(prompt.contains("### CITABLE EVIDENCE METADATA"), prompt);
        assertTrue(prompt.contains("Only markers in this block are public citations"), prompt);
        assertTrue(prompt.contains("[W1] kind=WEB"), prompt);
        assertTrue(prompt.contains("lines=5"), prompt);
        assertTrue(prompt.indexOf("### CITABLE EVIDENCE METADATA") < prompt.indexOf("### SEARCH RESULTS"), prompt);
        assertTrue(prompt.contains("[W1] alpha searchable body"), prompt);
        assertEquals(1, TraceStore.get("prompt.citableEvidenceRenderedCount"));
        assertEquals(false, TraceStore.get("promptBuilder.evidenceEmpty"));
    }

    @Test
    void systemInstructionDoesNotBypassCitableEvidenceMetadata() {
        PromptContext ctx = PromptContext.builder()
                .systemInstruction("trusted system rule")
                .evidence(List.of(new RagEvidenceMetadata(
                        "W1", "WEB", "Alpha", "https://example.com/a", null,
                        5, null, 1, 0.91d, "score")))
                .build();

        String prompt = builder.build(ctx);

        assertTrue(prompt.contains("trusted system rule"), prompt);
        assertTrue(prompt.contains("### CITABLE EVIDENCE METADATA"), prompt);
        assertTrue(prompt.contains("[W1] kind=WEB"), prompt);
    }

    @Test
    void emptyEvidenceIsNullSafe() {
        PromptContext ctx = PromptContext.builder().build();

        String prompt = builder.build(List.of(ctx), "no evidence");

        assertTrue(prompt.contains("### SEARCH RESULTS"), prompt);
        assertEquals(0, TraceStore.get("prompt.citableEvidenceRenderedCount"));
        assertEquals(true, TraceStore.get("promptBuilder.evidenceEmpty"));
    }

    @Test
    void oldVerifiedSummaryMarkersRemainHistoryWithoutCurrentEvidence() {
        String old = "Entity A [W1] VERIFIED https://old.example/a";
        PromptContext ctx = PromptContext.builder().memory(old).history("Assistant: " + old).build();

        String prompt = builder.build(List.of(ctx), "Entity B?");

        assertTrue(prompt.contains("### MEMORY\n" + old));
        assertTrue(prompt.contains("### RECENT CONVERSATION\nAssistant: " + old));
        assertFalse(prompt.contains("### CITABLE EVIDENCE METADATA"));
        assertEquals(0, TraceStore.get("prompt.citableEvidenceRenderedCount"));
        assertEquals(true, TraceStore.get("promptBuilder.evidenceEmpty"));
    }

    @Test
    void currentEvidenceMarkerDoesNotPromoteTheSameMarkerFromOldHistory() {
        String old = "Entity A [W1] VERIFIED https://old.example/a";
        Content current = Content.from(TextSegment.from("Entity B qualified current evidence",
                Metadata.from(Map.of("url", "https://current.example/b"))));
        PromptContext ctx = PromptContext.builder().memory(old).history("Assistant: " + old)
                .web(List.of(current)).evidence(List.of(new RagEvidenceMetadata(
                        "W1", "WEB", "Entity B", "https://current.example/b", null,
                        1, null, 1, 0.9d, "score"))).build();

        String prompt = builder.build(List.of(ctx), "Entity B?");
        String citations = prompt.split("### CITABLE EVIDENCE METADATA\\n", 2)[1].split("\\n\\n", 2)[0];

        assertTrue(prompt.contains("### MEMORY\n" + old));
        assertTrue(citations.contains("[W1] kind=WEB; title=Entity B; source=https://current.example/b"));
        assertFalse(citations.contains("https://old.example/a"));
        assertEquals(1, TraceStore.get("prompt.citableEvidenceRenderedCount"));
    }

    @Test
    void projectFeatureInventoryGuardPreventsExternalHomonymEvidenceClaims() {
        Content web = Content.from(TextSegment.from(
                "GraphRAG toolkit for AWS knowledge graphs. CFVM means Control Flow Virtual Machine.",
                Metadata.from(Map.of("title", "External GraphRAG and CFVM", "url", "https://example.com/cfvm"))));
        PromptContext ctx = PromptContext.builder()
                .web(List.of(web))
                .build();

        String prompt = builder.build(List.of(ctx),
                "Dynamic RAG Orchestration Platform에서 Plan DSL, MoE Strategy Selector, GraphRAG/KG, CFVM 중 "
                        + "실제 근거가 있는 항목과 evidence_needed 항목을 source marker로 나눠줘.");

        assertTrue(prompt.contains("### PROJECT FEATURE EVIDENCE GUARD"), prompt);
        assertTrue(prompt.contains("External web homonyms do not prove demo-1 or project-local features"), prompt);
        assertTrue(prompt.contains("Do not cite [W] search-result markers as source markers for these project features"), prompt);
        assertTrue(prompt.contains("CFVM must stay tied to project-local CFVM source markers"), prompt);
        assertTrue(prompt.contains("external acronym homonym without spelling it out"), prompt);
        assertEquals(true, TraceStore.get("prompt.projectFeatureEvidenceGuard"));
    }

    @Test
    void projectFeatureInventoryIncludesProjectLocalSourceHints() {
        assertTrue(Files.exists(Path.of("main/resources/plans/brave.v1.yaml")));
        assertTrue(Files.exists(Path.of("main/java/com/example/lms/moe/RgbStrategySelector.java")));
        assertTrue(Files.exists(Path.of("main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java")));
        assertTrue(Files.exists(Path.of("main/java/com/example/lms/cfvm/RawMatrixBuffer.java")));

        Content web = Content.from(TextSegment.from(
                "GraphRAG toolkit for AWS knowledge graphs. CFVM means Control Flow Virtual Machine.",
                Metadata.from(Map.of("title", "External GraphRAG and CFVM", "url", "https://example.com/cfvm"))));
        PromptContext ctx = PromptContext.builder()
                .web(List.of(web))
                .build();

        String prompt = builder.build(List.of(ctx),
                "Dynamic RAG Orchestration Platform에서 Plan DSL, MoE Strategy Selector, GraphRAG/KG, CFVM 중 "
                        + "실제 근거가 있는 항목과 evidence_needed 항목을 source marker로 나눠줘.");

        assertTrue(prompt.contains("### PROJECT-LOCAL FEATURE SOURCE HINTS"), prompt);
        assertTrue(prompt.contains("[P1] Plan DSL"), prompt);
        assertTrue(prompt.contains("main/resources/plans/brave.v1.yaml"), prompt);
        assertTrue(prompt.contains("[P2] MoE Strategy Selector"), prompt);
        assertTrue(prompt.contains("main/java/com/example/lms/moe/RgbStrategySelector.java"), prompt);
        assertTrue(prompt.contains("[P3] GraphRAG/KG"), prompt);
        assertTrue(prompt.contains("main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java"), prompt);
        assertTrue(prompt.contains("[P4] CFVM"), prompt);
        assertTrue(prompt.contains("main/java/com/example/lms/cfvm/RawMatrixBuffer.java"), prompt);
        assertEquals(true, TraceStore.get("prompt.projectFeatureSourceHintsRendered"));
    }

    @Test
    void projectFeatureInventoryGuardIsAlsoInstructionLevelForRuntimeChatWorkflow() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("Dynamic RAG Orchestration Platform에서 Plan DSL, MoE Strategy Selector, GraphRAG/KG, CFVM 중 "
                        + "실제 근거가 있는 항목과 evidence_needed 항목을 source marker로 나눠줘.")
                .web(List.of(Content.from(TextSegment.from(
                        "GraphRAG toolkit for AWS knowledge graphs. CFVM means Control Flow Virtual Machine.",
                        Metadata.from(Map.of("title", "External GraphRAG and CFVM", "url", "https://example.com/cfvm"))))))
                .build();

        String instructions = builder.buildInstructions(ctx);

        assertTrue(instructions.contains("### PROJECT FEATURE EVIDENCE GUARD"), instructions);
        assertTrue(instructions.contains("External web homonyms do not prove demo-1 or project-local features"), instructions);
        assertTrue(instructions.contains("CFVM must stay tied to project-local CFVM source markers"), instructions);
        assertTrue(instructions.contains("When available sources are generic external web pages only"), instructions);
        assertTrue(instructions.contains("do not classify GraphRAG/KG, CFVM, Plan DSL, or MoE Strategy Selector as supported"), instructions);
        assertTrue(instructions.contains("Do not introduce or repeat alternate CFVM expansions unless the user explicitly asks about acronym meanings"), instructions);
        assertTrue(instructions.contains("external acronym homonym without spelling it out"), instructions);
        assertFalse(instructions.contains("Control Flow Virtual Machine"), instructions);
        assertTrue(instructions.contains("When no project-scoped source marker exists, do not claim external documents mention or imply support"), instructions);
        assertEquals(true, TraceStore.get("prompt.projectFeatureEvidenceGuard"));
    }
}
