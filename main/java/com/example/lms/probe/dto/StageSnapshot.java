package com.example.lms.probe.dto;

import java.util.*;

public class StageSnapshot {
    public String name;
    public Map<String,Object> params = new HashMap<>();
    public java.util.List<CandidateDTO> candidates = new ArrayList<>();
}