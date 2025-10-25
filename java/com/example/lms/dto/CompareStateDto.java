package com.example.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;




/**
 * Data transfer object mirroring the fields of {@link com.example.lms.compare.state.CompareState}
 * for use in request payloads. This DTO allows the frontend to specify
 * comparison parameters when sending a {@link ChatRequestDto}. All fields
 * are optional; missing values will be initialised with sensible defaults
 * by downstream services.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompareStateDto {
    private List<String> entities;
    private List<String> criteria;
    private Map<String, Double> weights;
    private List<String> teamContext;
    private Map<String, Object> constraints;
    private Boolean explain;
}