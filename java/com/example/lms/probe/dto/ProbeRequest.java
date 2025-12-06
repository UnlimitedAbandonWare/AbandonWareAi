package com.example.lms.probe.dto;

import lombok.*;



@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProbeRequest {
    private String query;
    private String useWebSearch;       // "true"/"false"
    private String useRag;             // "false"면 web-only 테스트
    private String officialSourcesOnly;
    private String webTopK;
    private String searchMode;         // "AUTO", "FORCE_DEEP" 등
    private String intent;             // FINANCE|COMPANY|GENERAL

    private String useBm25;
    private String useSelfAsk;
    private String forceBranch;
    private String planId;
    private String timeBudgetMs;
}