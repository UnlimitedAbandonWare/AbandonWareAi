package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.file.FileIngestionService;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.storage.LocalFileStorageService;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

class ChatWorkflowInteractionPolicyRuntimeFactsTest {

    @TempDir
    Path tempDir;

    @Test
    void attachmentDigestProvenanceAndAuthorizationResultsFeedWorkflowDecisionBoundary() throws Exception {
        LocalFileStorageService storage = mock(LocalFileStorageService.class);
        when(storage.save(any(), eq("chat"))).thenAnswer(invocation -> {
            org.springframework.web.multipart.MultipartFile upload = invocation.getArgument(0);
            Path target = tempDir.resolve(upload.getOriginalFilename());
            Files.write(target, upload.getBytes());
            return target.toString();
        });
        AttachmentService attachments = new AttachmentService(storage, mock(FileIngestionService.class));
        ReflectionTestUtils.setField(attachments, "environment",
                new MockEnvironment().withProperty("interaction.evidence-neutral.mode", "enforce"));
        AttachmentDto saved = attachments.saveAll(List.of(
                new MockMultipartFile("file", "evidence.txt", "text/plain", "verified evidence".getBytes())),
                "session-a").get(0);
        Path stored = tempDir.resolve("evidence.txt");

        GuardContext sameSession = GuardContext.defaultContext();
        attachments.observeInteractionEvidence(List.of(saved.id()), "session-a", sameSession);
        InteractionEvidencePolicy.Decision sameSessionDecision = ChatWorkflow.resolveInteractionPolicyDecision(
                "Summarize the attachment.", sameSession, "enforce");
        assertEquals(InteractionEvidencePolicy.SecurityStance.NEUTRAL, sameSessionDecision.securityStance());

        Files.writeString(stored, "tampered evidence");
        GuardContext digestMismatch = GuardContext.defaultContext();
        attachments.observeInteractionEvidence(List.of(saved.id()), "session-a", digestMismatch);
        InteractionEvidencePolicy.Decision digestDecision = ChatWorkflow.resolveInteractionPolicyDecision(
                "Summarize the attachment.", digestMismatch, "enforce");
        assertEquals(InteractionEvidencePolicy.SecurityStance.DEFENSIVE, digestDecision.securityStance());
        assertTrue(digestDecision.proofKinds().contains(InteractionEvidencePolicy.ProofKind.DIGEST_MISMATCH));
        assertEquals(Set.of(saved.id()), digestMismatch.getInteractionSuspectEvidenceIds());

        AttachmentDto tamperedBeforeFirstObservation = attachments.saveAll(List.of(
                new MockMultipartFile(
                        "file", "before-first.txt", "text/plain", "original evidence".getBytes())),
                "session-a").get(0);
        Files.writeString(tempDir.resolve("before-first.txt"), "tampered before first observation");
        GuardContext preFirstObservationMismatch = GuardContext.defaultContext();
        attachments.observeInteractionEvidence(
                List.of(tamperedBeforeFirstObservation.id()), "session-a", preFirstObservationMismatch);
        InteractionEvidencePolicy.Decision preFirstDecision = ChatWorkflow.resolveInteractionPolicyDecision(
                "Summarize the attachment.", preFirstObservationMismatch, "enforce");
        assertTrue(preFirstDecision.proofKinds().contains(
                InteractionEvidencePolicy.ProofKind.DIGEST_MISMATCH));
        assertEquals(Set.of(tamperedBeforeFirstObservation.id()),
                preFirstObservationMismatch.getInteractionSuspectEvidenceIds());

        GuardContext foreignSession = GuardContext.defaultContext();
        attachments.observeInteractionEvidence(List.of(saved.id()), "session-b", foreignSession);
        InteractionEvidencePolicy.Decision foreignDecision = ChatWorkflow.resolveInteractionPolicyDecision(
                "Summarize the attachment.", foreignSession, "enforce");
        assertEquals(InteractionEvidencePolicy.SecurityStance.DEFENSIVE, foreignDecision.securityStance());
        assertTrue(foreignDecision.proofKinds().contains(
                InteractionEvidencePolicy.ProofKind.AUTHORIZATION_DENIED));
        assertTrue(foreignDecision.proofKinds().contains(
                InteractionEvidencePolicy.ProofKind.PROVENANCE_MISMATCH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void offModeSkipsUploadDigestAndInteractionObservation() throws Exception {
        LocalFileStorageService storage = mock(LocalFileStorageService.class);
        Path stored = tempDir.resolve("off-mode.txt");
        when(storage.save(any(), eq("chat"))).thenAnswer(invocation -> {
            org.springframework.web.multipart.MultipartFile upload = invocation.getArgument(0);
            Files.write(stored, upload.getBytes());
            return stored.toString();
        });
        AttachmentService attachments = new AttachmentService(storage, mock(FileIngestionService.class));
        ReflectionTestUtils.setField(attachments, "environment",
                new MockEnvironment().withProperty("interaction.evidence-neutral.mode", "off"));
        AttachmentDto saved = attachments.saveAll(List.of(
                new MockMultipartFile("file", "off-mode.txt", "text/plain", "original".getBytes())),
                "session-a").get(0);

        Map<String, String> capturedDigests = (Map<String, String>) ReflectionTestUtils.getField(
                attachments, "contentDigestById");
        assertTrue(capturedDigests.isEmpty());
        Files.writeString(stored, "tampered");
        GuardContext context = GuardContext.defaultContext();
        attachments.observeInteractionEvidence(List.of(saved.id()), "session-b", context);

        assertTrue(context.getInteractionPolicyFacts().isEmpty());
        assertTrue(context.getInteractionSuspectEvidenceIds().isEmpty());
    }

    @Test
    void promptEvidenceFilterRemovesOnlyTheBoundedSuspect() {
        Content clean = content("https://evidence.invalid/clean", "clean evidence");
        Content suspect = content("https://evidence.invalid/suspect", "suspect evidence");

        List<Content> filtered = ChatWorkflow.filterSuspectPromptContents(
                List.of(clean, suspect), Set.of("https://evidence.invalid/suspect"), false);

        assertEquals(1, filtered.size());
        assertSame(clean, filtered.get(0));
    }

    private static Content content(String url, String text) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("url", url))));
    }
}
