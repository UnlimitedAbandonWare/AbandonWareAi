package com.example.lms.dto;

import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.List;

@Getter
public class ChatResponseDto {

    private final String content;
    private final Long sessionId;
    private final String modelUsed;
    private final boolean ragUsed;
    private final String answerMode;
    private final Long traceTurnId;
    private final LearningContextMetadata learningContext;
    private final List<RagEvidenceMetadata> evidence;
    private final ChatStreamEvent.PipelineSnapshot pipelineSnapshot;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final SelectionEntropyProjection selectionEntropy;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final GenerationTermination generationTermination;

    /** Numeric/categorical receipt only. Provider error messages and request bodies stay private. */
    public record GenerationTermination(String status, String reason, String incompleteReason,
            String finishReason, boolean partial, boolean hasText, String responseId,
            Integer inputTokens, Integer outputTokens, Integer totalTokens, String providerCode) {
        public static GenerationTermination from(com.example.lms.llm.gateway.LlmResponseTerminalException terminal) {
            var metadata = terminal.metadata();
            var usage = metadata.tokenUsage();
            boolean hasText = terminal.partialText() != null && !terminal.partialText().isBlank();
            return new GenerationTermination(terminal.status(), terminal.reasonCode(), terminal.incompleteReason(),
                    metadata.finishReason() == null ? null : metadata.finishReason().name(),
                    "incomplete".equals(terminal.status()) && hasText, hasText, metadata.id(),
                    usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount(), terminal.providerCode());
        }
    }

    public static ChatResponseDto terminal(com.example.lms.llm.gateway.LlmResponseTerminalException terminal,
            Long sessionId) {
        return new ChatResponseDto(terminal.partialText() == null ? "" : terminal.partialText(), sessionId,
                terminal.metadata().modelName(), false, null, null, LearningContextMetadata.empty(),
                List.of(), null, null, GenerationTermination.from(terminal));
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed) {
        this(content, sessionId, modelUsed, ragUsed, null);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, LearningContextMetadata.empty());
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           LearningContextMetadata learningContext) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, learningContext, List.of());
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, null, learningContext, evidence);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           Long traceTurnId,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, traceTurnId, learningContext, evidence, null);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           Long traceTurnId,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence,
                           ChatStreamEvent.PipelineSnapshot pipelineSnapshot) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, traceTurnId, learningContext,
                evidence, pipelineSnapshot, null);
    }

    public ChatResponseDto(String content,
                           Long sessionId,
                           String modelUsed,
                           boolean ragUsed,
                           String answerMode,
                           Long traceTurnId,
                           LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence,
                           ChatStreamEvent.PipelineSnapshot pipelineSnapshot,
                           SelectionEntropyProjection selectionEntropy) {
        this(content, sessionId, modelUsed, ragUsed, answerMode, traceTurnId, learningContext,
                evidence, pipelineSnapshot, selectionEntropy, null);
    }

    private ChatResponseDto(String content, Long sessionId, String modelUsed, boolean ragUsed,
                           String answerMode, Long traceTurnId, LearningContextMetadata learningContext,
                           List<RagEvidenceMetadata> evidence, ChatStreamEvent.PipelineSnapshot pipelineSnapshot,
                           SelectionEntropyProjection selectionEntropy, GenerationTermination generationTermination) {
        this.content = content;
        this.sessionId = sessionId;
        this.modelUsed = modelUsed;
        this.ragUsed = ragUsed;
        this.answerMode = answerMode;
        this.traceTurnId = traceTurnId == null ? null : Math.max(0L, traceTurnId);
        this.learningContext = learningContext == null ? LearningContextMetadata.empty() : learningContext;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        this.pipelineSnapshot = pipelineSnapshot;
        this.selectionEntropy = selectionEntropy;
        this.generationTermination = generationTermination;
    }
}
