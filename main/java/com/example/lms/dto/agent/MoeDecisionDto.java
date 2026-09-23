package com.example.lms.dto.agent;

import java.util.List;

public record MoeDecisionDto(
        String primaryStrategy,
        List<String> fallbackStrategies,
        int redScore,
        int greenScore,
        int blueScore,
        List<ScoreEventDto> scoreEvents,
        List<ReasonDto> reasons,
        String selectedPlate,
        double plateScore,
        String rolloutReason,
        int rolloutPercent) {
}
