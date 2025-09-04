package com.example.lms.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Simple link DTO used to surface fused (web+vector) links in responses.
 */
@Data
@Builder
public class LinkDto {
    private String title;
    private String url;
    private String source; // web | vector | web+vector
    private Double score;  // fused score or source score
}
