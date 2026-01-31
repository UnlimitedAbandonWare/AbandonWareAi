package com.example.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



/**
 * Data transfer object describing an image generation or editing task.
 *
 * <p>This object is embedded within {@link ChatRequestDto} when the
 * client requests that the server generate, edit or create a variation
 * of an image.  The mode field determines which operation to perform.
 * Optional fields (maskBase64 and size) may be ignored depending on
 * the selected mode.  All string fields should be trimmed and
 * validated by the caller.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageTask {
    /**
     * The type of image operation to perform.  Valid values are
     * GENERATE, EDIT and VARIATION.  When null or empty it defaults
     * to GENERATE.
     */
    private String mode;

    /**
     * The text prompt describing what to generate or how to edit the
     * image.  This field must not be null when submitting a request.
     */
    private String prompt;

    /**
     * Optional base64-encoded mask image.  Used only for EDIT or
     * VARIATION modes.  The string should not include a data URI
     * prefix.
     */
    private String maskBase64;

    /**
     * The desired image size in pixels (square).  If unspecified the
     * server will use a sensible default (e.g. 1024).
     */
    private Integer size;
}