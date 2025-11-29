package com.example.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



/**
 * Minimal DTO used by ranking utilities for representing links.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkDto {
    private String title;
    private String url;
    /**
     * Source of the link, e.g. "web", "vector", or "web+vector".
     */
    private String source;
    private double score;
}