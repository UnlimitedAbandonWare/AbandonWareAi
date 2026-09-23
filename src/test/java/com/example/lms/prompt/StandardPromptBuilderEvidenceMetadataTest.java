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
