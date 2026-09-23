package com.abandonwareai.evolver;

import org.springframework.stereotype.Component;

@Component
public class ArtPlateEvolver {
    // : generate new plate candidates based on telemetry
    public String proposeNewPlate(){ return "ap_candidate_v1"; }

}