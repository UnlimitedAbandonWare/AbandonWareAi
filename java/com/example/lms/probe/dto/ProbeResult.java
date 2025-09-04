package com.example.lms.probe.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProbeResult {
    private String query;
    private String normalized;
    private Map<String, Object> metaEcho;
    private int total;
    private Map<String, Long> byDomain;
    private long financeNoiseCount;
    private long officialCount;
    private long technologiesHits;
    private double financeNoiseRatio;
    private boolean pass;
    private List<ProbeDoc> docs;
}
