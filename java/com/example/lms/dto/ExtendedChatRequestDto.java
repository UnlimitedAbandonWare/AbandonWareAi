package com.example.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Collections;
import java.util.List;

/**
 * Extended chat request DTO that augments the base {@link ChatRequestDto}
 * with additional fields for file-based retrieval and image generation.
 *
 * <p>This subclass introduces three new properties:
 * <ul>
 *   <li>{@code fileSearch} – when true, the retrieval pipeline will index
 *       any uploaded files and incorporate their contents into the RAG
 *       context.  Defaults to false.</li>
 *   <li>{@code imageTask} – optional description of an image generation,
 *       editing or variation operation.  When non-null, the controller
 *       delegates to the {@code ImageGenerationService} to fulfil the
 *       request.</li>
 *   <li>{@code uploadedFileUrls} – a list of relative URLs pointing to
 *       files persisted by the server.  This list is populated by
 *       {@code ChatApiController} after handling multipart uploads and
 *       should be treated as read-only by clients.</li>
 * </ul>
 * Clients should use this type when submitting requests that include
 * attachments or image generation instructions.  It extends the base
 * ChatRequestDto so that existing service code can continue to accept
 * instances of the parent class without modification.</p>
 */
@Deprecated
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SuperBuilder(toBuilder = true)
public class ExtendedChatRequestDto extends ChatRequestDto {

    /**
     * Whether to perform retrieval over any uploaded documents.  When true
     * the system will index and summarize the contents of the uploaded files
     * and feed them into the retrieval chain.  Defaults to false.
     */
    @Builder.Default
    private Boolean fileSearch = false;

    /**
     * Optional image generation/editing task.  When null, no image
     * processing will occur.  See {@link ImageTask} for a description
     * of the supported modes and parameters.
     */
    private ImageTask imageTask;

    /**
     * List of uploaded file URLs relative to the server's context root.
     * This field is populated by the controller once files have been
     * persisted to the configured upload directory.  Clients should not
     * supply values for this property.
     */
    @Builder.Default
    private List<String> uploadedFileUrls = Collections.emptyList();

    /**
     * Constructs an ExtendedChatRequestDto with the base request fields
     * copied from the given parent.  Additional properties default to
     * their initial values (fileSearch=false, imageTask=null, uploadedFileUrls=[]).
     *
     * @param parent the base chat request to copy
     */
    // src/main/java/com/example/lms/dto/ExtendedChatRequestDto.java
    public ExtendedChatRequestDto(ChatRequestDto parent) {
        super(
                parent.isUseVerification(),   // ← boolean 이면 is*
                parent.getMessage(),
                parent.getSystemPrompt(),
                parent.getHistory(),
                parent.getModel(),
                parent.getTemperature(),
                parent.getTopP(),
                parent.getFrequencyPenalty(),
                parent.getPresencePenalty(),
                parent.getMaxTokens(),
                parent.getSessionId(),
                parent.isUseRag(),
                parent.isUseWebSearch(),
                parent.getRagStandalone(),
                parent.isUseAdaptive(),
                parent.isAutoTranslate(),
                parent.isPolish(),
                parent.isUnderstandingEnabled(),
                parent.getInputType(),
                parent.getMaxMemoryTokens(),
                parent.getMaxRagTokens(),
                parent.getSearchMode(),
                parent.getWebProviders(),
                parent.getWebTopK(),
                parent.getOfficialSourcesOnly(),
                parent.getSearchScopes(),
                parent.getImageBase64(),
                /* ▼ ChatRequestDto의 마지막 필드 */
                false // webSearchExplicit (외부 입력 아님, 기본값 false 유지)
        );
    }
}