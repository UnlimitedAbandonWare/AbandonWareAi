package com.example.lms.probe.dto;

import java.util.*;

public class SearchProbeResponse {
    public List<StageSnapshot> stages = new ArrayList<>();
    public List<CandidateDTO> finalResults = new ArrayList<>();
}